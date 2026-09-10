package io.huocode.api.service;

import io.huocode.api.aggregation.RepoAggregator;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.exception.ReportNotFoundException;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.ReportStore;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RepoUrlAnalyzerService {

  private final RepoAggregator repoAggregator;
  private final ReportStore reportStore;

  public AnalysisResult analyze(RepoUrl repoUrl, String sha) throws IOException {
    AnalysisResult cached = cachedFor(repoUrl, sha).orElse(null);
    if (cached != null) {
      return cached;
    }
    AnalysisResult result = repoAggregator.analyze(repoUrl, sha);
    reportStore.save(repoUrl, sha, result);
    return result;
  }

  public Optional<AnalysisResult> cachedFor(RepoUrl repoUrl, String sha) {
    return reportStore
        .findByRepoAndSha(repoUrl, sha)
        .map(result -> result.strategy(AnalysisResult.StrategyEnum.CACHED));
  }

  public AnalysisResult getLatestReport(RepoUrl repoUrl) {
    return reportStore
        .findLatest(repoUrl)
        .orElseThrow(
            () ->
                new ReportNotFoundException(
                    "no cached result for " + repoUrl + ", trigger an analysis first"));
  }
}
