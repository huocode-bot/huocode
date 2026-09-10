package io.huocode.api.conf;

import java.time.Duration;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class AnalysisProperties {

  private final String githubToken;
  private final Duration githubRequestTimeout;
  private final long apiDirectFileThreshold;
  private final long hardFileLimit;
  private final int minFilesForRelativeScoring;
  private final int commitWindow;
  private final int exceedsCommonComplexityThreshold;
  private final Duration gitCommandTimeout;
  private final long maxFileSizeBytes;
  private final Duration jobTtl;
  private final int asyncEstimatedSeconds;
  private final Duration asyncProcessingWindow;
  private final int perIpAnalysisPerHour;
  private final int retryAfterSeconds;
  private final int maxConcurrentSyncAnalysis;
  private final int maxInflightAsyncJobs;

  public AnalysisProperties(
      @Value("${GITHUB_TOKEN:}") String githubToken,
      @Value("${huocode.github-request-timeout:PT30S}") Duration githubRequestTimeout,
      @Value("${huocode.api-direct-file-threshold:300}") long apiDirectFileThreshold,
      @Value("${huocode.hard-file-limit:3000}") long hardFileLimit,
      @Value("${huocode.min-files-for-relative-scoring:15}") int minFilesForRelativeScoring,
      @Value("${huocode.commit-window:500}") int commitWindow,
      @Value("${huocode.exceeds-common-complexity-threshold:10}")
          int exceedsCommonComplexityThreshold,
      @Value("${huocode.git-command-timeout:PT5M}") Duration gitCommandTimeout,
      @Value("${huocode.max-file-size-bytes:1048576}") long maxFileSizeBytes,
      @Value("${huocode.job-ttl:PT1H}") Duration jobTtl,
      @Value("${huocode.async-estimated-seconds:60}") int asyncEstimatedSeconds,
      @Value("${huocode.async-processing-window:PT15M}") Duration asyncProcessingWindow,
      @Value("${huocode.per-ip-analysis-per-hour:10}") int perIpAnalysisPerHour,
      @Value("${huocode.retry-after-seconds:60}") int retryAfterSeconds,
      @Value("${huocode.max-concurrent-sync-analysis:2}") int maxConcurrentSyncAnalysis,
      @Value("${huocode.max-inflight-async-jobs:10}") int maxInflightAsyncJobs) {
    this.githubToken = githubToken;
    this.githubRequestTimeout = githubRequestTimeout;
    this.apiDirectFileThreshold = apiDirectFileThreshold;
    this.hardFileLimit = hardFileLimit;
    this.minFilesForRelativeScoring = minFilesForRelativeScoring;
    this.commitWindow = commitWindow;
    this.exceedsCommonComplexityThreshold = exceedsCommonComplexityThreshold;
    this.gitCommandTimeout = gitCommandTimeout;
    this.maxFileSizeBytes = maxFileSizeBytes;
    this.jobTtl = jobTtl;
    this.asyncEstimatedSeconds = asyncEstimatedSeconds;
    this.asyncProcessingWindow = asyncProcessingWindow;
    this.perIpAnalysisPerHour = perIpAnalysisPerHour;
    this.retryAfterSeconds = retryAfterSeconds;
    this.maxConcurrentSyncAnalysis = maxConcurrentSyncAnalysis;
    this.maxInflightAsyncJobs = maxInflightAsyncJobs;
  }
}
