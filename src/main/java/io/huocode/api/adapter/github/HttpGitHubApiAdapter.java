package io.huocode.api.adapter.github;

import static io.huocode.api.adapter.github.CommitReader.commitChurn;
import static io.huocode.api.adapter.github.CommitReader.fetchWindow;

import com.fasterxml.jackson.databind.JsonNode;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import io.huocode.api.utils.ChurnParser;
import io.huocode.api.utils.ChurnParser.CommitChurn;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

@Component
public class HttpGitHubApiAdapter implements GitHubApiPort {

  private static final int MAX_CONCURRENT_FETCHES = 8;

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
  public RepoChurn repoChurn(RepoUrl repoUrl, String commitSha, int maxCommits) {
    List<JsonNode> commits = fetchWindow(http, repoUrl, commitSha, maxCommits);
    int threads = Math.max(1, Math.min(MAX_CONCURRENT_FETCHES, commits.size()));
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      // One detail request per commit, shared by every file it touches. Failures propagate:
      // a missing diff must fail the analysis, never be scored as zero churn.
      List<CompletableFuture<CommitChurn>> details =
          commits.stream()
              .map(
                  item ->
                      CompletableFuture.supplyAsync(
                          () -> commitChurn(http, repoUrl, item), executor))
              .toList();
      List<CommitChurn> windowCommits = new ArrayList<>(details.size());
      for (CompletableFuture<CommitChurn> detail : details) {
        windowCommits.add(join(detail));
      }
      return ChurnParser.aggregate(windowCommits);
    } finally {
      executor.shutdownNow();
    }
  }

  private static <T> T join(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException(cause);
    }
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
