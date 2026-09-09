package io.huocode.api.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiDirectRetrieverTest {

  private static final RepoUrl REPO = new RepoUrl("owner", "repo");
  private static final AnalysisProperties PROPERTIES =
      new AnalysisProperties(
          "", Duration.ofSeconds(5), 300, 20000, 15, 500, 10, Duration.ofSeconds(10), 1048576);
  private static final AnalysisWindowData WINDOW =
      new AnalysisWindowData(
          10, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-10T00:00:00Z"), 3);

  @Test
  void retrieve_fetches_content_churn_and_window_for_every_file() {
    StubGitHubApiPort port = new StubGitHubApiPort();
    ApiDirectRetriever retriever = new ApiDirectRetriever(port, PROPERTIES);

    ApiDirectRetrieverData data = retriever.retrieve(REPO);

    assertEquals("sha1", data.sha());
    assertEquals(List.of("A.java", "src/B.java"), data.files());
    assertEquals("content:A.java", data.contents().get("A.java"));
    assertEquals("content:src/B.java", data.contents().get("src/B.java"));
    assertEquals(new Churn("A.java".length(), 2), data.churnByPath().get("A.java"));
    assertEquals(new Churn("src/B.java".length(), 2), data.churnByPath().get("src/B.java"));
    assertEquals(500, port.churnCalls.get("A.java"));
    assertEquals(500, port.churnCalls.get("src/B.java"));
    assertEquals(WINDOW, data.window());
  }

  private static final class StubGitHubApiPort implements GitHubApiPort {

    final Map<String, Integer> churnCalls = new HashMap<>();

    @Override
    public boolean repoExists(RepoUrl repoUrl) {
      return true;
    }

    @Override
    public String defaultBranch(RepoUrl repoUrl) {
      return "main";
    }

    @Override
    public String latestCommitSha(RepoUrl repoUrl) {
      return "sha1";
    }

    @Override
    public List<String> listAllPaths(RepoUrl repoUrl, String commitSha) {
      return List.of("A.java", "src/B.java");
    }

    @Override
    public long fileCount(RepoUrl repoUrl, String commitSha) {
      return 2;
    }

    @Override
    public String rawContent(RepoUrl repoUrl, String path, String commitSha) {
      return "content:" + path;
    }

    @Override
    public Churn churnForPath(RepoUrl repoUrl, String path, int maxCommits) {
      churnCalls.put(path, maxCommits);
      return new Churn(path.length(), 2);
    }

    @Override
    public AnalysisWindowData analysisWindow(RepoUrl repoUrl, int maxCommits) {
      return WINDOW;
    }
  }
}
