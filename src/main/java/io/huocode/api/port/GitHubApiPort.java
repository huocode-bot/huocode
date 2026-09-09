package io.huocode.api.port;

import io.huocode.api.model.RepoUrl;
import java.util.List;

public interface GitHubApiPort {

  boolean repoExists(RepoUrl repoUrl);

  String defaultBranch(RepoUrl repoUrl);

  String latestCommitSha(RepoUrl repoUrl);

  List<String> listAllPaths(RepoUrl repoUrl, String commitSha);

  long fileCount(RepoUrl repoUrl, String commitSha);

  String rawContent(RepoUrl repoUrl, String path, String commitSha);
}
