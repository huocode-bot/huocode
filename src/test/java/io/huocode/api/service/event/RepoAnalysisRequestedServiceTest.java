package io.huocode.api.service.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.endpoint.event.model.RepoAnalysisRequested;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AsyncFailureCode;
import io.huocode.api.exception.GitHubRateLimitReachedException;
import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.JobStore;
import io.huocode.api.service.RepoUrlAnalyzerService;
import io.huocode.api.validation.RepoUrlValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RepoAnalysisRequestedServiceTest {

  private static final Duration WINDOW = Duration.ofMinutes(15);

  private final JobStore jobStore = Mockito.mock(JobStore.class);
  private final RepoUrlAnalyzerService analyzerService = Mockito.mock(RepoUrlAnalyzerService.class);
  private final RepoUrlValidator repoUrlValidator = new RepoUrlValidator();
  private final AnalysisProperties properties =
      new AnalysisProperties(
          "",
          Duration.ofSeconds(5),
          300,
          20000,
          15,
          10,
          500,
          Duration.ofSeconds(10),
          1048576,
          Duration.ofHours(48),
          60,
          WINDOW,
          10,
          60,
          2);
  private final RepoAnalysisRequestedService service =
      new RepoAnalysisRequestedService(jobStore, analyzerService, repoUrlValidator, properties);

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";
  private final UUID jobId = UUID.randomUUID();

  @Test
  void accept_completes_pending_job_and_clears_active_pointer() throws Exception {
    Instant now = Instant.now();
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);
    AnalysisResult result = new AnalysisResult();
    when(jobStore.findById(jobId)).thenReturn(Optional.of(job));
    when(analyzerService.analyze(repoUrl)).thenReturn(result);

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    ArgumentCaptor<RepoAnalysisJob> saved = ArgumentCaptor.forClass(RepoAnalysisJob.class);
    verify(jobStore, times(2)).save(saved.capture());
    assertNotNull(saved.getAllValues().get(0).getStartedAt());
    assertEquals(RepoAnalysisJob.Status.COMPLETED, saved.getAllValues().get(1).getStatus());
    assertSame(result, saved.getAllValues().get(1).getResult());
    verify(jobStore).clearActive(repoUrl, sha, jobId);
  }

  @Test
  void accept_ignores_unknown_job() throws Exception {
    when(jobStore.findById(jobId)).thenReturn(Optional.empty());

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    verify(analyzerService, never()).analyze(any());
    verify(jobStore, never()).save(any());
  }

  @Test
  void accept_ignores_terminal_job() throws Exception {
    Instant now = Instant.now();
    RepoAnalysisJob failed =
        RepoAnalysisJob.pending(repoUrl, sha, jobId, now).failed(AsyncFailureCode.ANALYSIS_TIMEOUT);
    when(jobStore.findById(jobId)).thenReturn(Optional.of(failed));

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    verify(analyzerService, never()).analyze(any());
    verify(jobStore, never()).save(any());
  }

  @Test
  void accept_ignores_freshly_started_job() throws Exception {
    Instant now = Instant.now();
    RepoAnalysisJob running =
        RepoAnalysisJob.pending(repoUrl, sha, jobId, now.minus(Duration.ofMinutes(2)))
            .started(now.minus(Duration.ofMinutes(1)));
    when(jobStore.findById(jobId)).thenReturn(Optional.of(running));

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    verify(analyzerService, never()).analyze(any());
    verify(jobStore, never()).save(any());
  }

  @Test
  void accept_reprocesses_stale_job() throws Exception {
    Instant now = Instant.now();
    RepoAnalysisJob stale =
        RepoAnalysisJob.pending(repoUrl, sha, jobId, now.minus(Duration.ofMinutes(30)))
            .started(now.minus(Duration.ofMinutes(20)));
    AnalysisResult result = new AnalysisResult();
    when(jobStore.findById(jobId)).thenReturn(Optional.of(stale));
    when(analyzerService.analyze(repoUrl)).thenReturn(result);

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    verify(analyzerService).analyze(repoUrl);
    verify(jobStore, times(2)).save(any());
    verify(jobStore).clearActive(repoUrl, sha, jobId);
  }

  @Test
  void accept_marks_job_failed_with_rate_limit_on_github_rejection() throws Exception {
    Instant now = Instant.now();
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);
    when(jobStore.findById(jobId)).thenReturn(Optional.of(job));
    when(analyzerService.analyze(repoUrl))
        .thenThrow(new GitHubRateLimitReachedException("rate limited"));

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    RepoAnalysisJob failed = lastSaved();
    assertEquals(RepoAnalysisJob.Status.FAILED, failed.getStatus());
    assertEquals(AsyncFailureCode.GITHUB_RATE_LIMIT_REACHED, failed.getErrorCode());
    verify(jobStore).clearActive(repoUrl, sha, jobId);
  }

  @Test
  void accept_marks_job_failed_with_timeout_on_unexpected_error() throws Exception {
    Instant now = Instant.now();
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, jobId, now);
    when(jobStore.findById(jobId)).thenReturn(Optional.of(job));
    when(analyzerService.analyze(repoUrl)).thenThrow(new IllegalStateException("boom"));

    service.accept(new RepoAnalysisRequested(jobId, repoUrl.canonicalUrl()));

    RepoAnalysisJob failed = lastSaved();
    assertEquals(RepoAnalysisJob.Status.FAILED, failed.getStatus());
    assertEquals(AsyncFailureCode.ANALYSIS_TIMEOUT, failed.getErrorCode());
    verify(jobStore).clearActive(repoUrl, sha, jobId);
  }

  private RepoAnalysisJob lastSaved() {
    ArgumentCaptor<RepoAnalysisJob> saved = ArgumentCaptor.forClass(RepoAnalysisJob.class);
    verify(jobStore, times(2)).save(saved.capture());
    return saved.getAllValues().get(1);
  }
}
