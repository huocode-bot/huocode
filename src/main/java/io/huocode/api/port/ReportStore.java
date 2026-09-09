package io.huocode.api.port;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.model.RepoUrl;
import java.util.Optional;

public interface ReportStore {

  Optional<AnalysisResult> findLatest(RepoUrl repoUrl);

  Optional<AnalysisResult> findByRepoAndSha(RepoUrl repoUrl, String sha);

  void save(RepoUrl repoUrl, String sha, AnalysisResult result);
}
