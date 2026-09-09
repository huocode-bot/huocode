package io.huocode.api.port;

import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import java.nio.file.Path;
import java.util.List;

public interface GitScmPort {

  Path clone(RepoUrl repoUrl, Path targetDirectory);

  List<String> trackedFiles(Path repository);

  RepoChurn logNumstat(Path repository);

  String latestCommitSha(Path repository);
}
