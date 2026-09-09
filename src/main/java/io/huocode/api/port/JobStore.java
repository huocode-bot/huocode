package io.huocode.api.port;

import io.huocode.api.model.RepoAnalysisJob;
import io.huocode.api.model.RepoUrl;
import java.util.Optional;
import java.util.UUID;

public interface JobStore {

  long countActive();

  Optional<RepoAnalysisJob> findById(UUID jobId);

  Optional<RepoAnalysisJob> findActive(RepoUrl repoUrl, String commitSha);

  void registerActive(RepoAnalysisJob job);

  void save(RepoAnalysisJob job);

  void clearActive(RepoUrl repoUrl, String commitSha, UUID jobId);

  void delete(UUID jobId);
}
