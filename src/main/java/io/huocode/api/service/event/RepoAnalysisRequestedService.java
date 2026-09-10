package io.huocode.api.service.event;

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
import java.net.URI;
import java.time.Instant;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepoAnalysisRequestedService implements Consumer<RepoAnalysisRequested> {

  private final JobStore jobStore;
  private final RepoUrlAnalyzerService analyzerService;
  private final RepoUrlValidator repoUrlValidator;
  private final AnalysisProperties properties;

  @Override
  public void accept(RepoAnalysisRequested event) {
    RepoAnalysisJob job = jobStore.findById(event.getJobId()).orElse(null);
    if (job == null) {
      return;
    }
    if (job.isTerminal()
        || job.isFreshlyStarted(properties.getAsyncProcessingWindow(), Instant.now())) {
      return;
    }
    try {
      RepoUrl repoUrl = repoUrlValidator.validate(URI.create(job.canonicalUrl()));
      jobStore.save(job.started(Instant.now()));
      AnalysisResult result = analyzerService.analyze(repoUrl, job.getCommitSha());
      jobStore.save(job.completed(result));
      jobStore.clearActive(repoUrl, job.getCommitSha(), job.getJobId());
    } catch (GitHubRateLimitReachedException e) {
      fail(job, AsyncFailureCode.GITHUB_RATE_LIMIT_REACHED, e);
    } catch (Exception e) {
      fail(job, AsyncFailureCode.ANALYSIS_TIMEOUT, e);
    }
  }

  private void fail(RepoAnalysisJob job, AsyncFailureCode code, Exception error) {
    log.error("analysis job {} failed", job.getJobId(), error);
    jobStore.save(job.failed(code));
    try {
      RepoUrl repoUrl = repoUrlValidator.validate(URI.create(job.canonicalUrl()));
      jobStore.clearActive(repoUrl, job.getCommitSha(), job.getJobId());
    } catch (RuntimeException e) {
      log.warn("could not clear active pointer for failed job {}", job.getJobId(), e);
    }
  }
}
