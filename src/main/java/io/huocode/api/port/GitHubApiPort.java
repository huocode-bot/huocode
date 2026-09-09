package io.huocode.api.port;

import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoUrl;
import java.util.List;

public interface GitHubApiPort {

  boolean repoExists(RepoUrl repoUrl);

  String defaultBranch(RepoUrl repoUrl);

  String latestCommitSha(RepoUrl repoUrl);

  List<String> listAllPaths(RepoUrl repoUrl, String commitSha);

  long fileCount(RepoUrl repoUrl, String commitSha);

  String rawContent(RepoUrl repoUrl, String path, String commitSha);

  Churn churnForPath(RepoUrl repoUrl, String path, int maxCommits);

  AnalysisWindowData analysisWindow(RepoUrl repoUrl, int maxCommits);
}
