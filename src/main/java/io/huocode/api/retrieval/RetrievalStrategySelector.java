package io.huocode.api.retrieval;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.RepoTooLargeException;
import io.huocode.api.model.RetrievalStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RetrievalStrategySelector {

  private final AnalysisProperties properties;

  public RetrievalStrategy select(long fileCount) {
    if (fileCount <= properties.getApiDirectFileThreshold()) {
      return RetrievalStrategy.API_DIRECT;
    }
    if (fileCount <= properties.getHardFileLimit()) {
      return RetrievalStrategy.CLONE;
    }
    throw new RepoTooLargeException(
        "repo has "
            + fileCount
            + " files, exceeding the limit of "
            + properties.getHardFileLimit());
  }
}
