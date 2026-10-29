package io.huocode.api.port;

import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import java.util.List;

public interface GitHubApiPort {

  boolean repoExists(RepoUrl repoUrl);

  String defaultBranch(RepoUrl repoUrl);

  String latestCommitSha(RepoUrl repoUrl);

  List<String> listAllPaths(RepoUrl repoUrl, String commitSha);

  long fileCount(RepoUrl repoUrl, String commitSha);

  String rawContent(RepoUrl repoUrl, String path, String commitSha);

  /** Churn of the last maxCommits non-merge commits reachable from commitSha. */
  RepoChurn repoChurn(RepoUrl repoUrl, String commitSha, int maxCommits);
}
