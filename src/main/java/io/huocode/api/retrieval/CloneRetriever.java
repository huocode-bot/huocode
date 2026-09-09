package io.huocode.api.retrieval;

import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitScmPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CloneRetriever {

  private static final String CLONE_DIRECTORY = "clone";

  private final GitScmPort gitScmPort;

  public CloneRetrieverData retrieve(RepoUrl repoUrl) throws IOException {
    Path tempParent = Files.createTempDirectory("huocode-git-");
    try {
      Path cloneDirectory = tempParent.resolve(CLONE_DIRECTORY);
      gitScmPort.clone(repoUrl, cloneDirectory);
      return new CloneRetrieverData(
          gitScmPort.latestCommitSha(cloneDirectory),
          gitScmPort.trackedFiles(cloneDirectory),
          gitScmPort.logNumstat(cloneDirectory));
    } finally {
      deleteRecursively(tempParent);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException ignored) {
                  // best effort cleanup of the temporary clone
                }
              });
    }
  }
}
