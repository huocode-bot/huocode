package io.huocode.api.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiDirectRetrieverTest {

  private static final RepoUrl REPO = new RepoUrl("owner", "repo");
  private static final AnalysisProperties PROPERTIES =
      new AnalysisProperties(
          "",
          Duration.ofSeconds(5),
          300,
          20000,
          15,
          500,
          10,
          Duration.ofSeconds(10),
          1048576,
          Duration.ofHours(48),
          60,
          Duration.ofMinutes(15),
          10,
          60,
          2,
          10);
  private static final AnalysisWindowData WINDOW =
      new AnalysisWindowData(
          10, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-10T00:00:00Z"), 3);
  private static final Map<String, Churn> CHURN = Map.of("A.java", new Churn(2, 1, 5, 1));

  @Test
  void retrieve_fetches_java_contents_and_the_repo_churn_once() {
    StubGitHubApiPort port = new StubGitHubApiPort();
    ApiDirectRetriever retriever = new ApiDirectRetriever(port, PROPERTIES);

    ApiDirectRetrieverData data = retriever.retrieve(REPO, "sha1");

    assertEquals("sha1", data.sha());
    assertEquals(List.of("A.java", "src/B.java", "README.md"), data.files());
    assertEquals("content:A.java", data.contents().get("A.java"));
    assertEquals("content:src/B.java", data.contents().get("src/B.java"));
    assertEquals(2, data.contents().size());
    assertEquals(CHURN, data.churnByPath());
    assertEquals(List.of("sha1:500"), port.churnCalls);
    assertEquals(WINDOW, data.window());
  }

  private static final class StubGitHubApiPort implements GitHubApiPort {

    final List<String> churnCalls = new ArrayList<>();

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
      return List.of("A.java", "src/B.java", "README.md");
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
    public synchronized RepoChurn repoChurn(RepoUrl repoUrl, String commitSha, int maxCommits) {
      churnCalls.add(commitSha + ":" + maxCommits);
      return new RepoChurn(CHURN, WINDOW);
    }
  }
}
