package io.huocode.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.huocode.api.aggregation.RepoAggregator;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.exception.ReportNotFoundException;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.ReportStore;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RepoUrlAnalyzerServiceTest {

  private final RepoAggregator repoAggregator = Mockito.mock(RepoAggregator.class);
  private final ReportStore reportStore = Mockito.mock(ReportStore.class);
  private final RepoUrlAnalyzerService service =
      new RepoUrlAnalyzerService(repoAggregator, reportStore);

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";

  @Test
  void analyze_returns_cached_result_marked_cached() throws IOException {
    AnalysisResult cached =
        aResult(AnalysisResult.StrategyEnum.CLONE).repoHealthScore(42).summary(null);
    when(reportStore.findByRepoAndSha(repoUrl, sha)).thenReturn(Optional.of(cached));

    AnalysisResult result = service.analyze(repoUrl, sha);

    assertSame(cached, result);
    assertEquals(AnalysisResult.StrategyEnum.CACHED, result.getStrategy());
    assertEquals(42, result.getRepoHealthScore());
    verify(repoAggregator, never()).analyze(any(), any());
    verify(reportStore, never()).save(any(), any(), any());
  }

  @Test
  void analyze_computes_and_stores_when_not_cached() throws IOException {
    AnalysisResult fresh = aResult(AnalysisResult.StrategyEnum.API_DIRECT);
    when(reportStore.findByRepoAndSha(repoUrl, sha)).thenReturn(Optional.empty());
    when(repoAggregator.analyze(repoUrl, sha)).thenReturn(fresh);

    AnalysisResult result = service.analyze(repoUrl, sha);

    assertSame(fresh, result);
    assertEquals(AnalysisResult.StrategyEnum.API_DIRECT, result.getStrategy());
    verify(reportStore).save(repoUrl, sha, fresh);
  }

  @Test
  void get_latest_report_returns_found_report() {
    AnalysisResult stored = aResult(AnalysisResult.StrategyEnum.CLONE);
    when(reportStore.findLatest(repoUrl)).thenReturn(Optional.of(stored));

    assertSame(stored, service.getLatestReport(repoUrl));
  }

  @Test
  void cached_for_marks_result_cached_when_present() {
    AnalysisResult stored = aResult(AnalysisResult.StrategyEnum.CLONE).repoHealthScore(42);
    when(reportStore.findByRepoAndSha(repoUrl, sha)).thenReturn(Optional.of(stored));

    Optional<AnalysisResult> found = service.cachedFor(repoUrl, sha);

    assertSame(stored, found.orElseThrow());
    assertEquals(AnalysisResult.StrategyEnum.CACHED, found.orElseThrow().getStrategy());
    assertEquals(42, found.orElseThrow().getRepoHealthScore());
  }

  @Test
  void cached_for_is_empty_when_result_absent() {
    when(reportStore.findByRepoAndSha(repoUrl, sha)).thenReturn(Optional.empty());

    assertTrue(service.cachedFor(repoUrl, sha).isEmpty());
  }

  @Test
  void get_latest_report_throws_when_absent() {
    when(reportStore.findLatest(repoUrl)).thenReturn(Optional.empty());

    assertThrows(ReportNotFoundException.class, () -> service.getLatestReport(repoUrl));
    verify(reportStore).findLatest(eq(repoUrl));
  }

  private AnalysisResult aResult(AnalysisResult.StrategyEnum strategy) {
    return new AnalysisResult()
        .status(AnalysisResult.StatusEnum.COMPLETED)
        .repo(repoUrl.toString())
        .commitSha(sha)
        .analyzedAt(Instant.now())
        .strategy(strategy)
        .limitations(List.of())
        .analysisWindow(
            new AnalysisWindow()
                .commitsAnalyzed(10)
                .oldestCommitDate(Instant.now())
                .newestCommitDate(Instant.now())
                .distinctAuthors(2))
        .relativeScoringEnabled(false)
        .files(List.of())
        .top5(List.of());
  }
}
