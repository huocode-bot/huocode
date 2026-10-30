package io.huocode.api.adapter.github;

import static io.huocode.api.utils.ChurnParser.countChangedLines;
import static io.huocode.api.utils.ChurnParser.countPatchLines;
import static io.huocode.api.utils.ChurnParser.isBot;

import com.fasterxml.jackson.databind.JsonNode;
import io.huocode.api.exception.GitHubRateLimitReachedException;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.utils.ChurnParser.CommitChurn;
import io.huocode.api.utils.ChurnParser.LineCounts;
import io.huocode.api.utils.ChurnParser.PathChange;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the commit window through the GitHub API with the same semantics as the clone path
 * (JGitChurnReader): the last non-merge commits reachable from the analyzed commit, one diff per
 * commit, blank lines excluded, renames followed.
 */
public final class CommitReader {

  private static final int PAGE_SIZE = 100;
  // GitHub serves at most 3000 files per commit, 100 per page.
  private static final int MAX_FILE_PAGES = 30;
  // Each omitted patch costs up to two content requests: bounded per commit.
  private static final int MAX_CONTENT_DIFFS_PER_COMMIT = 20;

  private CommitReader() {}

  /**
   * Commits from commitSha until maxCommits non-merge commits are collected. The merges met on the
   * way are kept too, for ancestry only.
   */
  public static List<JsonNode> fetchWindow(
      GitHubApiHttp http, RepoUrl repoUrl, String commitSha, int maxCommits) {
    List<JsonNode> commits = new ArrayList<>();
    int nonMerges = 0;
    int page = 1;
    while (nonMerges < maxCommits) {
      JsonNode items = http.getJson(repoUrl, commitsUrl(http, repoUrl, commitSha, page));
      if (items.isEmpty()) {
        break;
      }
      for (JsonNode item : items) {
        commits.add(item);
        if (!isMerge(item) && ++nonMerges >= maxCommits) {
          break;
        }
      }
      if (items.size() < PAGE_SIZE) {
        break;
      }
      page++;
    }
    return commits;
  }

  public static boolean isMerge(JsonNode item) {
    return item.path("parents").size() > 1;
  }

  /** The commit and its diff; a merge is returned without its diff, which is never fetched. */
  public static CommitChurn commitChurn(GitHubApiHttp http, RepoUrl repoUrl, JsonNode item) {
    String sha = item.path("sha").asText();
    List<String> parentIds = new ArrayList<>();
    for (JsonNode parent : item.path("parents")) {
      parentIds.add(parent.path("sha").asText());
    }
    List<PathChange> changes = new ArrayList<>();
    AtomicInteger contentDiffBudget = new AtomicInteger(MAX_CONTENT_DIFFS_PER_COMMIT);
    for (int page = 1; !isMerge(item) && page <= MAX_FILE_PAGES; page++) {
      JsonNode files =
          http.getJson(
                  repoUrl,
                  http.repoPath(repoUrl)
                      + "/commits/"
                      + GitHubApiHttp.encode(sha)
                      + "?per_page="
                      + PAGE_SIZE
                      + "&page="
                      + page)
              .path("files");
      String parentSha = parentIds.isEmpty() ? null : parentIds.get(0);
      for (JsonNode file : files) {
        changes.add(pathChange(http, repoUrl, sha, parentSha, file, contentDiffBudget));
      }
      if (files.size() < PAGE_SIZE) {
        break;
      }
    }
    return new CommitChurn(sha, parentIds, authorOf(item), dateOf(item), changes);
  }

  private static PathChange pathChange(
      GitHubApiHttp http,
      RepoUrl repoUrl,
      String sha,
      String parentSha,
      JsonNode file,
      AtomicInteger contentDiffBudget) {
    String path = file.path("filename").asText();
    String status = file.path("status").asText();
    String previousPath =
        "renamed".equals(status) ? file.path("previous_filename").asText(null) : null;
    LineCounts lines;
    if (file.hasNonNull("patch")) {
      lines = countPatchLines(file.get("patch").asText());
    } else if (file.path("additions").asInt(0) + file.path("deletions").asInt(0) == 0) {
      lines = new LineCounts(0, 0); // binary file or pure rename: no line changed
    } else if (contentDiffBudget.getAndDecrement() > 0) {
      // GitHub omits the patch of very large diffs; its raw counts would include blank lines, so
      // both versions are diffed here, the way the clone path does.
      lines = diffContents(http, repoUrl, sha, parentSha, status, path, previousPath, file);
    } else {
      lines = rawCounts(file); // budget spent: keep the request count of one commit bounded
    }
    return new PathChange(path, previousPath, lines.added(), lines.deleted());
  }

  private static LineCounts diffContents(
      GitHubApiHttp http,
      RepoUrl repoUrl,
      String sha,
      String parentSha,
      String status,
      String path,
      String previousPath,
      JsonNode file) {
    try {
      // A copy counts as an added file, as the clone path (rename detection only) sees it.
      boolean added = "added".equals(status) || "copied".equals(status) || parentSha == null;
      String before =
          added
              ? ""
              : content(http, repoUrl, previousPath != null ? previousPath : path, parentSha);
      String after = "removed".equals(status) ? "" : content(http, repoUrl, path, sha);
      return countChangedLines(before, after);
    } catch (GitHubRateLimitReachedException e) {
      throw e;
    } catch (RuntimeException e) {
      // A version GitHub will not serve (too large, missing at the parent): its raw counts are
      // the best figure left, rather than failing the whole analysis on one file.
      return rawCounts(file);
    }
  }

  private static LineCounts rawCounts(JsonNode file) {
    return new LineCounts(
        Math.max(0, file.path("additions").asInt(0)), Math.max(0, file.path("deletions").asInt(0)));
  }

  private static String content(GitHubApiHttp http, RepoUrl repoUrl, String path, String sha) {
    return http.getRaw(
        repoUrl,
        http.repoPath(repoUrl)
            + "/contents/"
            + GitHubApiHttp.encodePath(path)
            + "?ref="
            + GitHubApiHttp.encode(sha));
  }

  /** The git author name, as the clone path uses; a bot login wins so bots stay filtered. */
  private static String authorOf(JsonNode item) {
    String login = item.path("author").path("login").asText("");
    if (isBot(login)) {
      return login;
    }
    return item.path("commit").path("author").path("name").asText("");
  }

  /** The git author date, as the clone path uses. */
  private static Instant dateOf(JsonNode item) {
    String date = item.path("commit").path("author").path("date").asText(null);
    if (date == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(date).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String commitsUrl(
      GitHubApiHttp http, RepoUrl repoUrl, String commitSha, int page) {
    return http.repoPath(repoUrl)
        + "/commits?sha="
        + GitHubApiHttp.encode(commitSha)
        + "&per_page="
        + PAGE_SIZE
        + "&page="
        + page;
  }
}
