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

  public AnalysisProperties(
      @Value("${GITHUB_TOKEN:}") String githubToken,
      @Value("${huocode.github-request-timeout:PT30S}") Duration githubRequestTimeout,
      @Value("${huocode.api-direct-file-threshold:300}") long apiDirectFileThreshold,
      @Value("${huocode.hard-file-limit:20000}") long hardFileLimit,
      @Value("${huocode.min-files-for-relative-scoring:15}") int minFilesForRelativeScoring,
      @Value("${huocode.commit-window:500}") int commitWindow,
      @Value("${huocode.exceeds-common-complexity-threshold:10}")
          int exceedsCommonComplexityThreshold) {
    this.githubToken = githubToken;
    this.githubRequestTimeout = githubRequestTimeout;
    this.apiDirectFileThreshold = apiDirectFileThreshold;
    this.hardFileLimit = hardFileLimit;
    this.minFilesForRelativeScoring = minFilesForRelativeScoring;
    this.commitWindow = commitWindow;
    this.exceedsCommonComplexityThreshold = exceedsCommonComplexityThreshold;
  }
}
