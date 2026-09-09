package io.huocode.api.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.TestGitRepos;
import io.huocode.api.adapter.git.FileUrlGitCliAdapter;
import io.huocode.api.adapter.pmd.PmdAdapter;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisResult.StrategyEnum;
import io.huocode.api.endpoint.rest.model.FileErrorCode;
import io.huocode.api.endpoint.rest.model.FileResult;
import io.huocode.api.endpoint.rest.model.FileStatus;
import io.huocode.api.endpoint.rest.model.LimitationCode;
import io.huocode.api.endpoint.rest.model.Quadrant;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import io.huocode.api.retrieval.ApiDirectRetriever;
import io.huocode.api.retrieval.CloneRetriever;
import io.huocode.api.retrieval.RetrievalStrategySelector;
import io.huocode.api.scoring.ScoreEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoAggregatorTest {

  private static final AnalysisProperties PROPERTIES =
      new AnalysisProperties(
          "", Duration.ofSeconds(5), 300, 20000, 15, 500, 10, Duration.ofSeconds(10), 1048576);
  private static final AnalysisWindowData WINDOW =
      new AnalysisWindowData(
          16, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-10T00:00:00Z"), 3);

  @TempDir Path temp;

  @Test
  void analyze_uses_clone_strategy_and_maps_all_statuses() throws IOException {
    Path source = temp.resolve("source");
    TestGitRepos.init(source);
    TestGitRepos.write(source, "A.java", "class A {\n  void m(int a) { if (a > 0) { }\n  }\n}\n");
    TestGitRepos.write(
        source, "B.java", "class B {\n  void m(int a) { if (a > 0) { } else { }\n  }\n}\n");
    TestGitRepos.write(source, "Broken.java", "class Broken { void m() { return\n");
    TestGitRepos.write(source, "README.md", "readme\n");
    TestGitRepos.commit(source, "first commit");

    StubGitHubApiPort port = new StubGitHubApiPort(500, "stub", List.of());
    RepoAggregator aggregator =
        new RepoAggregator(
            new RetrievalStrategySelector(PROPERTIES),
            new CloneRetriever(new FileUrlGitCliAdapter(PROPERTIES, source)),
            new ApiDirectRetriever(port, PROPERTIES),
            port,
            new PmdAdapter(),
            new ScoreEngine(PROPERTIES),
            PROPERTIES);

    AnalysisResult result = aggregator.analyze(new RepoUrl("owner", "repo"));

    assertEquals(AnalysisResult.StatusEnum.COMPLETED, result.getStatus());
    assertEquals("owner/repo", result.getRepo());
    assertEquals(StrategyEnum.CLONE, result.getStrategy());
    assertEquals(40, result.getCommitSha().length());
    assertEquals(1, result.getAnalysisWindow().getCommitsAnalyzed());
    assertTrue(result.getRelativeScoringEnabled() == null || !result.getRelativeScoringEnabled());
    assertNull(result.getRepoHealthScore());
    assertNull(result.getSummary());
    assertTrue(result.getTop5().isEmpty());

    assertEquals(4, result.getFiles().size());
    FileResult a = fileFor(result, "A.java");
    assertEquals(FileStatus.ANALYZED, a.getStatus());
    assertTrue(a.getComplexity().intValue() > 0);
    assertEquals(1, a.getChurn().getCommits());
    assertNull(a.getComplexityPercentile());

    assertEquals(FileStatus.ERROR, fileFor(result, "Broken.java").getStatus());
    assertEquals(FileErrorCode.PARSE_ERROR, fileFor(result, "Broken.java").getErrorCode());
    assertEquals(FileStatus.UNSUPPORTED_LANGUAGE, fileFor(result, "README.md").getStatus());
    assertEquals("markdown", fileFor(result, "README.md").getUnsupportedLanguage());

    assertEquals(2, result.getLimitations().size());
    assertEquals(
        LimitationCode.UNSUPPORTED_LANGUAGE_FILES, result.getLimitations().get(0).getCode());
    assertEquals(1, result.getLimitations().get(0).getFileCount());
    assertEquals(
        LimitationCode.INSUFFICIENT_FILES_FOR_RELATIVE_SCORING,
        result.getLimitations().get(1).getCode());
    assertEquals(2, result.getLimitations().get(1).getFileCount());
  }

  @Test
  void analyze_uses_api_direct_and_enables_relative_scoring() throws IOException {
    List<String> paths = new ArrayList<>();
    Map<String, String> contents = new java.util.HashMap<>();
    Map<String, Churn> churns = new java.util.HashMap<>();
    for (int i = 1; i <= 16; i++) {
      String path = "F" + i + ".java";
      paths.add(path);
      contents.put(path, javaSource(i, i));
      churns.put(path, new Churn(i, 2));
    }
    paths.add("README.md");
    contents.put("README.md", "readme\n");
    churns.put("README.md", new Churn(1, 1));
    StubGitHubApiPort port = new StubGitHubApiPort(paths.size(), "abc123", paths);
    port.contents = contents;
    port.churns = churns;
    RepoAggregator aggregator =
        new RepoAggregator(
            new RetrievalStrategySelector(PROPERTIES),
            new CloneRetriever(new FileUrlGitCliAdapter(PROPERTIES, temp.resolve("unused"))),
            new ApiDirectRetriever(port, PROPERTIES),
            port,
            new PmdAdapter(),
            new ScoreEngine(PROPERTIES),
            PROPERTIES);

    AnalysisResult result = aggregator.analyze(new RepoUrl("owner", "repo"));

    assertEquals(StrategyEnum.API_DIRECT, result.getStrategy());
    assertEquals("abc123", result.getCommitSha());
    assertTrue(result.getRelativeScoringEnabled());
    assertNotNull(result.getRepoHealthScore());
    assertTrue(result.getRepoHealthScore() >= 0 && result.getRepoHealthScore() <= 100);
    assertNotNull(result.getSummary());
    assertEquals(1, result.getSummary().getUnanalyzed());
    assertTrue(result.getSummary().getHotspot() >= 1);
    assertEquals(17, result.getFiles().size());
    assertEquals(
        List.of(LimitationCode.UNSUPPORTED_LANGUAGE_FILES),
        result.getLimitations().stream().map(l -> l.getCode()).toList());
    assertEquals(1, result.getLimitations().get(0).getFileCount());

    FileResult f16 = fileFor(result, "F16.java");
    assertEquals(FileStatus.ANALYZED, f16.getStatus());
    assertEquals(100, f16.getComplexityPercentile());
    assertEquals(100, f16.getChurnPercentile());
    assertEquals(Quadrant.HOTSPOT, f16.getQuadrant());
    assertTrue(f16.getExceedsCommonComplexityThreshold());

    FileResult readme = fileFor(result, "README.md");
    assertEquals(FileStatus.UNSUPPORTED_LANGUAGE, readme.getStatus());
    assertEquals("markdown", readme.getUnsupportedLanguage());

    assertEquals(5, result.getTop5().size());
    assertEquals("F16.java", result.getTop5().get(0));
  }

  @Test
  void analyze_marks_oversized_files_as_file_too_large() throws IOException {
    AnalysisProperties smallLimits =
        new AnalysisProperties(
            "", Duration.ofSeconds(5), 300, 20000, 15, 500, 10, Duration.ofSeconds(10), 8);
    StubGitHubApiPort port = new StubGitHubApiPort(1, "abc", List.of("Big.java"));
    port.contents = Map.of("Big.java", "class Big {}\n");
    port.churns = Map.of("Big.java", new Churn(1, 1));
    RepoAggregator aggregator =
        new RepoAggregator(
            new RetrievalStrategySelector(smallLimits),
            new CloneRetriever(new FileUrlGitCliAdapter(smallLimits, temp.resolve("unused"))),
            new ApiDirectRetriever(port, smallLimits),
            port,
            new PmdAdapter(),
            new ScoreEngine(smallLimits),
            smallLimits);

    AnalysisResult result = aggregator.analyze(new RepoUrl("owner", "repo"));

    FileResult big = fileFor(result, "Big.java");
    assertEquals(FileStatus.ERROR, big.getStatus());
    assertEquals(FileErrorCode.FILE_TOO_LARGE, big.getErrorCode());
    assertEquals(1, result.getLimitations().size());
    assertEquals(
        LimitationCode.INSUFFICIENT_FILES_FOR_RELATIVE_SCORING,
        result.getLimitations().get(0).getCode());
    assertEquals(0, result.getLimitations().get(0).getFileCount());
  }

  private static FileResult fileFor(AnalysisResult result, String path) {
    return result.getFiles().stream()
        .filter(file -> file.getPath().equals(path))
        .findFirst()
        .orElseThrow();
  }

  private static String javaSource(int id, int ifCount) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < ifCount; i++) {
      body.append("    if (a == ").append(i).append(") { }\n");
    }
    return "class F" + id + " {\n  void m(int a) {\n" + body + "  }\n}\n";
  }

  private static final class StubGitHubApiPort implements GitHubApiPort {

    private final long fileCount;
    private final String sha;
    private final List<String> paths;
    private Map<String, String> contents = Map.of();
    private Map<String, Churn> churns = Map.of();

    private StubGitHubApiPort(long fileCount, String sha, List<String> paths) {
      this.fileCount = fileCount;
      this.sha = sha;
      this.paths = paths;
    }

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
      return sha;
    }

    @Override
    public List<String> listAllPaths(RepoUrl repoUrl, String commitSha) {
      return paths;
    }

    @Override
    public long fileCount(RepoUrl repoUrl, String commitSha) {
      return fileCount;
    }

    @Override
    public String rawContent(RepoUrl repoUrl, String path, String commitSha) {
      return contents.get(path);
    }

    @Override
    public Churn churnForPath(RepoUrl repoUrl, String path, int maxCommits) {
      return churns.get(path);
    }

    @Override
    public AnalysisWindowData analysisWindow(RepoUrl repoUrl, int maxCommits) {
      return WINDOW;
    }
  }
}
