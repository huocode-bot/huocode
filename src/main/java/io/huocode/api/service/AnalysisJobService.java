package io.huocode.api.service;

import io.huocode.api.concurrency.ConcurrencyGuard;
import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.endpoint.event.EventProducer;
import io.huocode.api.endpoint.event.model.RepoAnalysisRequested;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.JobAccepted;
import io.huocode.api.exception.JobNotFoundException;
import io.huocode.api.exception.QueueFullException;
import io.huocode.api.exception.RateLimitExceededException;
import io.huocode.api.mapper.AnalysisJobMapper;
import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.model.RetrievalStrategy;
import io.huocode.api.port.GitHubApiPort;
import io.huocode.api.port.JobStore;
import io.huocode.api.ratelimit.IpRateLimiter;
import io.huocode.api.retrieval.RetrievalStrategySelector;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobService {

  private final GitHubApiPort gitHubApiPort;
  private final RepoUrlAnalyzerService analyzerService;
  private final RetrievalStrategySelector strategySelector;
  private final JobStore jobStore;
  private final EventProducer<RepoAnalysisRequested> eventProducer;
  private final AnalysisJobMapper analysisJobMapper;
  private final AnalysisProperties properties;
  private final ConcurrencyGuard concurrencyGuard;
  private final IpRateLimiter ipRateLimiter;

  public record AnalysisSubmission(AnalysisResult result, JobAccepted jobAccepted) {

    public boolean isAsync() {
      return jobAccepted != null;
    }

    public static AnalysisSubmission synchronous(AnalysisResult result) {
      return new AnalysisSubmission(result, null);
    }

    public static AnalysisSubmission asynchronous(JobAccepted jobAccepted) {
      return new AnalysisSubmission(null, jobAccepted);
    }
  }

  public AnalysisSubmission submit(RepoUrl repoUrl, String clientIp) throws IOException {
    String sha = gitHubApiPort.latestCommitSha(repoUrl);
    AnalysisResult cached = analyzerService.cachedFor(repoUrl, sha).orElse(null);
    if (cached != null) {
      return AnalysisSubmission.synchronous(cached);
    }
    if (!ipRateLimiter.tryAcquire(clientIp)) {
      throw new RateLimitExceededException(
          "too many analyses triggered from this client, retry later",
          properties.getRetryAfterSeconds());
    }
    RetrievalStrategy strategy = strategySelector.select(gitHubApiPort.fileCount(repoUrl, sha));
    if (strategy == RetrievalStrategy.API_DIRECT) {
      if (!concurrencyGuard.tryAcquire()) {
        return AnalysisSubmission.asynchronous(
            analysisJobMapper.toAccepted(
                queueAsync(repoUrl, sha), properties.getAsyncEstimatedSeconds()));
      }
      try {
        return AnalysisSubmission.synchronous(analyzerService.analyze(repoUrl));
      } finally {
        concurrencyGuard.release();
      }
    }
    UUID jobId = queueAsync(repoUrl, sha);
    return AnalysisSubmission.asynchronous(
        analysisJobMapper.toAccepted(jobId, properties.getAsyncEstimatedSeconds()));
  }

  public Object getJob(UUID jobId) {
    RepoAnalysisJob job =
        jobStore
            .findById(jobId)
            .orElseThrow(() -> new JobNotFoundException("no analysis job for " + jobId));
    if (job.isExpired(properties.getJobTtl(), Instant.now())) {
      jobStore.delete(jobId);
      throw new JobNotFoundException("analysis job " + jobId + " expired");
    }
    return analysisJobMapper.toResponse(job);
  }

  private UUID queueAsync(RepoUrl repoUrl, String sha) {
    Optional<RepoAnalysisJob> active = jobStore.findActive(repoUrl, sha);
    if (active.isPresent()
        && active.get().isFreshlyStarted(properties.getAsyncProcessingWindow(), Instant.now())) {
      return active.get().getJobId();
    }
    if (jobStore.countActive() >= properties.getMaxInflightAsyncJobs()) {
      throw new QueueFullException(
          "too many analyses in flight, retry later", properties.getRetryAfterSeconds());
    }
    RepoAnalysisJob job = RepoAnalysisJob.pending(repoUrl, sha, UUID.randomUUID(), Instant.now());
    jobStore.registerActive(job);
    eventProducer.accept(
        List.of(new RepoAnalysisRequested(job.getJobId(), repoUrl.canonicalUrl())));
    return job.getJobId();
  }
}
