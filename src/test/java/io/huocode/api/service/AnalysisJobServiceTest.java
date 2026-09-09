package io.huocode.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.huocode.api.concurrency.ConcurrencyGuard;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.endpoint.event.EventProducer;
import io.huocode.api.endpoint.event.model.RepoAnalysisRequested;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.JobAccepted;
import io.huocode.api.endpoint.rest.model.JobProcessing;
import io.huocode.api.exception.JobNotFoundException;
import io.huocode.api.exception.RateLimitExceededException;
import io.huocode.api.exception.RepoTooLargeException;
import io.huocode.api.mapper.AnalysisJobMapper;
import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.model.RetrievalStrategy;
import io.huocode.api.port.GitHubApiPort;
import io.huocode.api.port.JobStore;
import io.huocode.api.ratelimit.IpRateLimiter;
import io.huocode.api.retrieval.RetrievalStrategySelector;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AnalysisJobServiceTest {

  private final GitHubApiPort gitHubApiPort = Mockito.mock(GitHubApiPort.class);
  private final RepoUrlAnalyzerService analyzerService = Mockito.mock(RepoUrlAnalyzerService.class);
  private final RetrievalStrategySelector strategySelector =
      Mockito.mock(RetrievalStrategySelector.class);
  private final JobStore jobStore = Mockito.mock(JobStore.class);
  private final EventProducer<RepoAnalysisRequested> eventProducer =
      Mockito.mock(EventProducer.class);
  private final AnalysisJobMapper analysisJobMapper = Mockito.mock(AnalysisJobMapper.class);
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
          Duration.ofMinutes(15),
          10,
          60,
          2);
  private final ConcurrencyGuard concurrencyGuard = new ConcurrencyGuard(properties);
  private final IpRateLimiter ipRateLimiter = new IpRateLimiter(properties);
  private final AnalysisJobService service =
      new AnalysisJobService(
          gitHubApiPort,
          analyzerService,
          strategySelector,
          jobStore,
          eventProducer,
          analysisJobMapper,
          properties,
          concurrencyGuard,
          ipRateLimiter);

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";

  @Test
  void submit_returns_cached_result_without_pointing_at_job() throws Exception {
    AnalysisResult cached = new AnalysisResult();
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.of(cached));

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4");

    assertFalse(submission.isAsync());
    assertSame(cached, submission.result());
    verify(strategySelector, never()).select(anyLong());
    verify(analyzerService, never()).analyze(any());
    verify(jobStore, never()).registerActive(any());
    verify(eventProducer, never()).accept(any());
  }

  @Test
  void submit_returns_sync_result_for_small_repo() throws Exception {
    AnalysisResult fresh = new AnalysisResult();
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(10L);
    when(strategySelector.select(10L)).thenReturn(RetrievalStrategy.API_DIRECT);
    when(analyzerService.analyze(repoUrl)).thenReturn(fresh);

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4");

    assertFalse(submission.isAsync());
    assertSame(fresh, submission.result());
    verify(jobStore, never()).registerActive(any());
    verify(eventProducer, never()).accept(any());
  }

  @Test
  void submit_returns_accepted_job_and_publishes_event_for_large_repo() throws Exception {
    JobAccepted accepted =
        new JobAccepted()
            .jobId(UUID.randomUUID())
            .status(JobAccepted.StatusEnum.PROCESSING)
            .estimatedSeconds(60);
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(1000L);
    when(strategySelector.select(1000L)).thenReturn(RetrievalStrategy.CLONE);
    when(jobStore.findActive(repoUrl, sha)).thenReturn(Optional.empty());
    when(analysisJobMapper.toAccepted(any(UUID.class), Mockito.eq(60))).thenReturn(accepted);

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4");

    assertTrue(submission.isAsync());
    assertSame(accepted, submission.jobAccepted());

    ArgumentCaptor<RepoAnalysisJob> jobCaptor = ArgumentCaptor.forClass(RepoAnalysisJob.class);
    verify(jobStore).registerActive(jobCaptor.capture());
    RepoAnalysisJob queued = jobCaptor.getValue();
    assertEquals("owner", queued.getOwner());
    assertEquals("repo", queued.getRepo());
    assertEquals(sha, queued.getCommitSha());

    ArgumentCaptor<Collection<RepoAnalysisRequested>> eventCaptor =
        ArgumentCaptor.forClass(Collection.class);
    verify(eventProducer).accept(eventCaptor.capture());
    RepoAnalysisRequested event = eventCaptor.getValue().iterator().next();
    assertEquals(queued.getJobId(), event.getJobId());
    assertEquals(repoUrl.canonicalUrl(), event.getRepoUrl());
  }

  @Test
  void submit_reuses_in_flight_job_without_publishing_again() throws Exception {
    Instant now = Instant.now();
    UUID inFlightId = UUID.randomUUID();
    RepoAnalysisJob active =
        RepoAnalysisJob.pending(repoUrl, sha, inFlightId, now.minus(Duration.ofMinutes(2)))
            .started(now.minus(Duration.ofMinutes(1)));
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(1000L);
    when(strategySelector.select(1000L)).thenReturn(RetrievalStrategy.CLONE);
    when(jobStore.findActive(repoUrl, sha)).thenReturn(Optional.of(active));
    when(analysisJobMapper.toAccepted(inFlightId, 60))
        .thenReturn(
            new JobAccepted()
                .jobId(inFlightId)
                .status(JobAccepted.StatusEnum.PROCESSING)
                .estimatedSeconds(60));

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4");

    assertTrue(submission.isAsync());
    assertEquals(inFlightId, submission.jobAccepted().getJobId());
    verify(jobStore, never()).registerActive(any());
    verify(eventProducer, never()).accept(any());
  }

  @Test
  void submit_propagates_repo_too_large() {
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(30000L);
    when(strategySelector.select(30000L))
        .thenThrow(new RepoTooLargeException("repo has too many files"));

    assertThrows(RepoTooLargeException.class, () -> service.submit(repoUrl, "1.2.3.4"));
    verify(jobStore, never()).registerActive(any());
    verify(eventProducer, never()).accept(any());
  }

  @Test
  void submit_rate_limits_triggered_analyses_per_ip() throws Exception {
    AnalysisProperties limited =
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
            Duration.ofMinutes(15),
            1,
            60,
            2);
    AnalysisJobService throttled =
        new AnalysisJobService(
            gitHubApiPort,
            analyzerService,
            strategySelector,
            jobStore,
            eventProducer,
            analysisJobMapper,
            limited,
            new ConcurrencyGuard(limited),
            new IpRateLimiter(limited));

    AnalysisResult fresh = new AnalysisResult();
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(10L);
    when(strategySelector.select(10L)).thenReturn(RetrievalStrategy.API_DIRECT);
    when(analyzerService.analyze(repoUrl)).thenReturn(fresh);

    assertFalse(throttled.submit(repoUrl, "1.2.3.4").isAsync());
    assertThrows(RateLimitExceededException.class, () -> throttled.submit(repoUrl, "1.2.3.4"));
    assertFalse(throttled.submit(repoUrl, "5.6.7.8").isAsync());
  }

  @Test
  void submit_converts_sync_analysis_to_async_when_concurrency_saturated() throws Exception {
    AnalysisProperties saturated =
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
            Duration.ofMinutes(15),
            10,
            60,
            1);
    ConcurrencyGuard saturatedGuard = new ConcurrencyGuard(saturated);
    saturatedGuard.tryAcquire();
    AnalysisJobService saturatedService =
        new AnalysisJobService(
            gitHubApiPort,
            analyzerService,
            strategySelector,
            jobStore,
            eventProducer,
            analysisJobMapper,
            saturated,
            saturatedGuard,
            new IpRateLimiter(saturated));

    JobAccepted accepted =
        new JobAccepted()
            .jobId(UUID.randomUUID())
            .status(JobAccepted.StatusEnum.PROCESSING)
            .estimatedSeconds(60);
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(10L);
    when(strategySelector.select(10L)).thenReturn(RetrievalStrategy.API_DIRECT);
    when(jobStore.findActive(repoUrl, sha)).thenReturn(Optional.empty());
    when(analysisJobMapper.toAccepted(any(UUID.class), Mockito.eq(60))).thenReturn(accepted);

    AnalysisSubmission submission = saturatedService.submit(repoUrl, "1.2.3.4");

    assertTrue(submission.isAsync());
    assertSame(accepted, submission.jobAccepted());
    verify(analyzerService, never()).analyze(any());
    verify(jobStore).registerActive(any(RepoAnalysisJob.class));
    verify(eventProducer).accept(any());
  }

  @Test
  void get_job_returns_mapped_response() {
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, UUID.randomUUID(), Instant.now());
    JobProcessing processing =
        new JobProcessing().jobId(job.getJobId()).status(JobProcessing.StatusEnum.PROCESSING);
    when(jobStore.findById(job.getJobId())).thenReturn(Optional.of(job));
    when(analysisJobMapper.toResponse(job)).thenReturn(processing);

    Object response = service.getJob(job.getJobId());

    assertSame(processing, response);
    verify(jobStore, never()).delete(any());
  }

  @Test
  void get_job_throws_when_not_found() {
    when(jobStore.findById(any(UUID.class))).thenReturn(Optional.empty());

    assertThrows(JobNotFoundException.class, () -> service.getJob(UUID.randomUUID()));
  }

  @Test
  void get_job_deletes_expired_job() {
    UUID expiredId = UUID.randomUUID();
    RepoAnalysisJob expired =
        RepoAnalysisJob.pending(repoUrl, sha, expiredId, Instant.now().minus(Duration.ofHours(49)));
    when(jobStore.findById(expiredId)).thenReturn(Optional.of(expired));

    assertThrows(JobNotFoundException.class, () -> service.getJob(expiredId));
    verify(jobStore).delete(expiredId);
  }

  @Test
  void job_durable_after_processing_without_expiry() {
    UUID jobId = UUID.randomUUID();
    RepoAnalysisJob fresh =
        RepoAnalysisJob.pending(repoUrl, sha, jobId, Instant.now())
            .completed(new AnalysisResult().status(AnalysisResult.StatusEnum.COMPLETED));
    JobProcessing processing =
        new JobProcessing().jobId(jobId).status(JobProcessing.StatusEnum.PROCESSING);
    when(jobStore.findById(jobId)).thenReturn(Optional.of(fresh));
    when(analysisJobMapper.toResponse(fresh)).thenReturn(processing);

    assertSame(processing, service.getJob(jobId));
    verify(jobStore, never()).delete(any());
  }
}
