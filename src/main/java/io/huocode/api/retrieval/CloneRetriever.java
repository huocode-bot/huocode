package io.huocode.api.retrieval;

import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitScmPort;
import java.io.IOException;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CloneRetriever {

  private static final String CLONE_DIRECTORY = "clone";

  private final GitScmPort gitScmPort;

  public CloneRetrieverData retrieve(RepoUrl repoUrl, Path parentDirectory) throws IOException {
    Path cloneDirectory = parentDirectory.resolve(CLONE_DIRECTORY);
    gitScmPort.clone(repoUrl, cloneDirectory);
    return new CloneRetrieverData(
        gitScmPort.latestCommitSha(cloneDirectory),
        gitScmPort.trackedFiles(cloneDirectory),
        gitScmPort.logNumstat(cloneDirectory));
  }
}
