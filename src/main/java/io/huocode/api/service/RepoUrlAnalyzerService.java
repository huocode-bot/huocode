package io.huocode.api.service;

import io.huocode.api.aggregation.RepoAggregator;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.exception.ReportNotFoundException;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitHubApiPort;
import io.huocode.api.port.ReportStore;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RepoUrlAnalyzerService {

  private final GitHubApiPort gitHubApiPort;
  private final RepoAggregator repoAggregator;
  private final ReportStore reportStore;

  public AnalysisResult analyze(RepoUrl repoUrl) throws IOException {
    String sha = gitHubApiPort.latestCommitSha(repoUrl);
    AnalysisResult cached = reportStore.findByRepoAndSha(repoUrl, sha).orElse(null);
    if (cached != null) {
      return cached.strategy(AnalysisResult.StrategyEnum.CACHED);
    }
    AnalysisResult result = repoAggregator.analyze(repoUrl);
    reportStore.save(repoUrl, sha, result);
    return result;
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
