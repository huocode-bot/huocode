package io.huocode.api.adapter.github;

import static io.huocode.api.adapter.github.CommitReader.authorOf;
import static io.huocode.api.adapter.github.CommitReader.dateOf;
import static io.huocode.api.adapter.github.CommitReader.fetchCommits;
import static io.huocode.api.adapter.github.CommitReader.isBotAuthor;

import com.fasterxml.jackson.databind.JsonNode;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HttpGitHubApiAdapter implements GitHubApiPort {

  private final GitHubApiHttp http;

  public HttpGitHubApiAdapter(GitHubApiHttp http) {
    this.http = http;
  }

  @Override
  public boolean repoExists(RepoUrl repoUrl) {
    try {
      http.getJson(repoUrl, http.repoPath(repoUrl));
      return true;
    } catch (RepoNotFoundException e) {
      return false;
    }
  }

  @Override
  public String defaultBranch(RepoUrl repoUrl) {
    return http.getJson(repoUrl, http.repoPath(repoUrl)).get("default_branch").asText();
  }

  @Override
  public String latestCommitSha(RepoUrl repoUrl) {
    String branch = defaultBranch(repoUrl);
    return http.getJson(
            repoUrl,
            http.repoPath(repoUrl) + "/commits/" + GitHubApiHttp.encode(branch) + "?per_page=1")
        .get("sha")
        .asText();
  }

  @Override
  public List<String> listAllPaths(RepoUrl repoUrl, String commitSha) {
    List<String> paths = new ArrayList<>();
    for (JsonNode entry : tree(repoUrl, commitSha)) {
      if ("blob".equals(entry.get("type").asText())) {
        paths.add(entry.get("path").asText());
      }
    }
    return paths;
  }

  @Override
  public long fileCount(RepoUrl repoUrl, String commitSha) {
    long count = 0;
    for (JsonNode entry : tree(repoUrl, commitSha)) {
      if ("blob".equals(entry.get("type").asText())) {
        count++;
      }
    }
    return count;
  }

  @Override
  public String rawContent(RepoUrl repoUrl, String path, String commitSha) {
    return http.getRaw(
        repoUrl,
        http.repoPath(repoUrl)
            + "/contents/"
            + GitHubApiHttp.encodePath(path)
            + "?ref="
            + GitHubApiHttp.encode(commitSha));
  }

  @Override
  public Churn churnForPath(RepoUrl repoUrl, String path, int maxCommits) {
    int commits = 0;
    Set<String> authors = new HashSet<>();
    int linesAdded = 0;
    int linesDeleted = 0;
    for (JsonNode item : fetchCommits(http, repoUrl, path, maxCommits)) {
      if (isBotAuthor(item)) {
        continue;
      }
      commits++;
      String author = authorOf(item);
      if (!author.isEmpty()) {
        authors.add(author);
      }
      // Commit detail (lines per file) may be unavailable (rate limit/404): the commit
      // then counts as touching 0 content lines — commit/author counts stay exact.
      Optional<CommitReader.FileLines> detail =
          CommitReader.fileLines(http, repoUrl, item.path("sha").asText(), path);
      if (detail.isPresent()) {
        linesAdded += detail.get().added();
        linesDeleted += detail.get().deleted();
      }
    }
    return commits == 0 && authors.isEmpty()
        ? Churn.ZERO
        : new Churn(commits, authors.size(), linesAdded, linesDeleted);
  }

  @Override
  public AnalysisWindowData analysisWindow(RepoUrl repoUrl, int maxCommits) {
    int commitsAnalyzed = 0;
    Set<String> authors = new HashSet<>();
    List<Instant> dates = new ArrayList<>();
    for (JsonNode item : fetchCommits(http, repoUrl, null, maxCommits)) {
      if (isBotAuthor(item)) {
        continue;
      }
      commitsAnalyzed++;
      String author = authorOf(item);
      if (!author.isEmpty()) {
        authors.add(author);
      }
      dateOf(item).ifPresent(dates::add);
    }
    Instant oldest = dates.stream().min(Instant::compareTo).orElse(null);
    Instant newest = dates.stream().max(Instant::compareTo).orElse(null);
    return new AnalysisWindowData(commitsAnalyzed, oldest, newest, authors.size());
  }

  private JsonNode tree(RepoUrl repoUrl, String commitSha) {
    return http.getJson(
            repoUrl,
            http.repoPath(repoUrl)
                + "/git/trees/"
                + GitHubApiHttp.encode(commitSha)
                + "?recursive=1")
        .get("tree");
  }
}
