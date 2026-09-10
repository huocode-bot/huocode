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
import io.huocode.api.exception.ChallengeFailedException;
import io.huocode.api.exception.ChallengeRequiredException;
import io.huocode.api.exception.JobNotFoundException;
import io.huocode.api.exception.QueueFullException;
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
import io.huocode.api.security.NoOpTurnstileVerifier;
import io.huocode.api.security.TurnstileGate;
import io.huocode.api.security.TurnstileVerifier;
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
          2,
          10);
  private final ConcurrencyGuard concurrencyGuard = new ConcurrencyGuard(properties);
  private final IpRateLimiter ipRateLimiter = new IpRateLimiter(properties);
  private final TurnstileVerifier noOpVerifier = new NoOpTurnstileVerifier();
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
          ipRateLimiter,
          disabledGate(ipRateLimiter));

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final String sha = "abc123";

  @Test
  void submit_returns_cached_result_without_pointing_at_job() throws Exception {
    AnalysisResult cached = new AnalysisResult();
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.of(cached));

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4", null);

    assertFalse(submission.isAsync());
    assertSame(cached, submission.result());
    verify(strategySelector, never()).select(anyLong());
    verify(analyzerService, never()).analyze(any(), any());
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
    when(analyzerService.analyze(repoUrl, sha)).thenReturn(fresh);

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4", null);

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

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4", null);

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

    AnalysisJobService.AnalysisSubmission submission = service.submit(repoUrl, "1.2.3.4", null);

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

    assertThrows(RepoTooLargeException.class, () -> service.submit(repoUrl, "1.2.3.4", null));
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
            2,
            10);
    IpRateLimiter limitedLimiter = new IpRateLimiter(limited);
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
            limitedLimiter,
            disabledGate(limitedLimiter));

    AnalysisResult fresh = new AnalysisResult();
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(10L);
    when(strategySelector.select(10L)).thenReturn(RetrievalStrategy.API_DIRECT);
    when(analyzerService.analyze(repoUrl, sha)).thenReturn(fresh);

    assertFalse(throttled.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertThrows(
        RateLimitExceededException.class, () -> throttled.submit(repoUrl, "1.2.3.4", null));
    assertFalse(throttled.submit(repoUrl, "5.6.7.8", null).isAsync());
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
            1,
            10);
    ConcurrencyGuard saturatedGuard = new ConcurrencyGuard(saturated);
    saturatedGuard.tryAcquire();
    IpRateLimiter saturatedLimiter = new IpRateLimiter(saturated);
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
            saturatedLimiter,
            disabledGate(saturatedLimiter));

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

    AnalysisJobService.AnalysisSubmission submission =
        saturatedService.submit(repoUrl, "1.2.3.4", null);

    assertTrue(submission.isAsync());
    assertSame(accepted, submission.jobAccepted());
    verify(analyzerService, never()).analyze(any(), any());
    verify(jobStore).registerActive(any(RepoAnalysisJob.class));
    verify(eventProducer).accept(any());
  }

  @Test
  void submit_rejects_with_queue_full_when_async_in_flight_at_capacity() throws Exception {
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
            2,
            0);
    IpRateLimiter saturatedLimiter = new IpRateLimiter(saturated);
    AnalysisJobService saturatedService =
        new AnalysisJobService(
            gitHubApiPort,
            analyzerService,
            strategySelector,
            jobStore,
            eventProducer,
            analysisJobMapper,
            saturated,
            new ConcurrencyGuard(saturated),
            saturatedLimiter,
            disabledGate(saturatedLimiter));

    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(1000L);
    when(strategySelector.select(1000L)).thenReturn(RetrievalStrategy.CLONE);
    when(jobStore.findActive(repoUrl, sha)).thenReturn(Optional.empty());
    when(jobStore.countActive()).thenReturn(0L);

    assertThrows(QueueFullException.class, () -> saturatedService.submit(repoUrl, "1.2.3.4", null));
    verify(jobStore, never()).registerActive(any(RepoAnalysisJob.class));
    verify(eventProducer, never()).accept(any());
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

  @Test
  void submit_challenges_ip_only_after_repeated_triggered_analyses() throws Exception {
    stubSmallRepoSyncAnalysis();
    AnalysisJobService stepUp = serviceWithTurnstile(noOpVerifier, true, 3);

    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertThrows(ChallengeRequiredException.class, () -> stepUp.submit(repoUrl, "1.2.3.4", null));
    assertFalse(stepUp.submit(repoUrl, "5.6.7.8", null).isAsync());
  }

  @Test
  void submit_accepts_valid_token_and_marks_ip_verified() throws Exception {
    TurnstileVerifier verifier = Mockito.mock(TurnstileVerifier.class);
    stubSmallRepoSyncAnalysis();
    AnalysisJobService stepUp = serviceWithTurnstile(verifier, true, 3);

    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", "good-token").isAsync());

    verify(verifier).verify("good-token");
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
  }

  @Test
  void submit_rejects_invalid_token_and_keeps_ip_unverified() throws Exception {
    TurnstileVerifier verifier = Mockito.mock(TurnstileVerifier.class);
    Mockito.doThrow(new ChallengeFailedException("bad token")).when(verifier).verify("bad-token");
    stubSmallRepoSyncAnalysis();
    AnalysisJobService stepUp = serviceWithTurnstile(verifier, true, 3);

    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertThrows(
        ChallengeFailedException.class, () -> stepUp.submit(repoUrl, "1.2.3.4", "bad-token"));
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertThrows(ChallengeRequiredException.class, () -> stepUp.submit(repoUrl, "1.2.3.4", null));

    verify(verifier).verify("bad-token");
  }

  @Test
  void submit_cached_hits_skip_challenge_even_past_threshold() throws Exception {
    stubSmallRepoSyncAnalysis();
    AnalysisJobService stepUp = serviceWithTurnstile(noOpVerifier, true, 3);

    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());

    AnalysisResult cached = new AnalysisResult();
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.of(cached));

    AnalysisJobService.AnalysisSubmission submission = stepUp.submit(repoUrl, "1.2.3.4", null);

    assertFalse(submission.isAsync());
    assertSame(cached, submission.result());
    assertEquals(3, ipRateLimiter.countFor("1.2.3.4"));

    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    assertThrows(ChallengeRequiredException.class, () -> stepUp.submit(repoUrl, "1.2.3.4", null));
  }

  @Test
  void submit_with_disabled_gate_never_challenges() throws Exception {
    stubSmallRepoSyncAnalysis();
    AnalysisJobService stepUp = serviceWithTurnstile(noOpVerifier, false, 3);

    for (int i = 0; i < 5; i++) {
      assertFalse(stepUp.submit(repoUrl, "1.2.3.4", null).isAsync());
    }
  }

  private AnalysisJobService serviceWithTurnstile(
      TurnstileVerifier verifier, boolean enabled, int challengeAfter) {
    return new AnalysisJobService(
        gitHubApiPort,
        analyzerService,
        strategySelector,
        jobStore,
        eventProducer,
        analysisJobMapper,
        properties,
        concurrencyGuard,
        ipRateLimiter,
        new TurnstileGate(verifier, ipRateLimiter, enabled, challengeAfter, Duration.ofHours(1)));
  }

  private TurnstileGate disabledGate(IpRateLimiter limiter) {
    return new TurnstileGate(noOpVerifier, limiter, false, 3, Duration.ofHours(1));
  }

  private void stubSmallRepoSyncAnalysis() throws Exception {
    when(gitHubApiPort.latestCommitSha(repoUrl)).thenReturn(sha);
    when(analyzerService.cachedFor(repoUrl, sha)).thenReturn(Optional.empty());
    when(gitHubApiPort.fileCount(repoUrl, sha)).thenReturn(10L);
    when(strategySelector.select(10L)).thenReturn(RetrievalStrategy.API_DIRECT);
    when(analyzerService.analyze(repoUrl, sha)).thenReturn(new AnalysisResult());
  }
}
