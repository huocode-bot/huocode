package io.huocode.api.adapter.github;

import static io.huocode.api.utils.ChurnParser.countPatchLines;
import static io.huocode.api.utils.ChurnParser.isBot;

import com.fasterxml.jackson.databind.JsonNode;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.utils.ChurnParser.CommitChurn;
import io.huocode.api.utils.ChurnParser.LineCounts;
import io.huocode.api.utils.ChurnParser.PathChange;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the commit window through the GitHub API with the same semantics as the clone path
 * (JGitChurnReader): the last non-merge commits reachable from the analyzed commit, one diff per
 * commit, blank lines excluded, renames followed.
 */
public final class CommitReader {

  private static final int PAGE_SIZE = 100;
  // GitHub serves at most 3000 files per commit, 100 per page.
  private static final int MAX_FILE_PAGES = 30;

  private CommitReader() {}

  public static List<JsonNode> fetchNonMergeCommits(
      GitHubApiHttp http, RepoUrl repoUrl, String commitSha, int maxCommits) {
    List<JsonNode> commits = new ArrayList<>();
    int page = 1;
    while (commits.size() < maxCommits) {
      JsonNode items = http.getJson(repoUrl, commitsUrl(http, repoUrl, commitSha, page));
      if (items.isEmpty()) {
        break;
      }
      for (JsonNode item : items) {
        if (item.path("parents").size() > 1) {
          continue;
        }
        commits.add(item);
        if (commits.size() >= maxCommits) {
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

  public static CommitChurn commitChurn(GitHubApiHttp http, RepoUrl repoUrl, JsonNode item) {
    String sha = item.path("sha").asText();
    List<PathChange> changes = new ArrayList<>();
    for (int page = 1; page <= MAX_FILE_PAGES; page++) {
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
      for (JsonNode file : files) {
        changes.add(pathChange(file));
      }
      if (files.size() < PAGE_SIZE) {
        break;
      }
    }
    return new CommitChurn(authorOf(item), dateOf(item), changes);
  }

  private static PathChange pathChange(JsonNode file) {
    String path = file.path("filename").asText();
    String previousPath =
        "renamed".equals(file.path("status").asText())
            ? file.path("previous_filename").asText(null)
            : null;
    LineCounts lines;
    if (file.hasNonNull("patch")) {
      lines = countPatchLines(file.get("patch").asText());
    } else {
      // GitHub omits the patch of binary and very large diffs: fall back to its raw line counts.
      lines =
          new LineCounts(
              Math.max(0, file.path("additions").asInt(0)),
              Math.max(0, file.path("deletions").asInt(0)));
    }
    return new PathChange(path, previousPath, lines.added(), lines.deleted());
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
