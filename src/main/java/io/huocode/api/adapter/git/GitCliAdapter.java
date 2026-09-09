package io.huocode.api.adapter.git;

import static io.huocode.api.utils.ChurnParser.parse;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitCommandFailedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitScmPort;
import io.huocode.api.utils.GitProcessUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GitCliAdapter implements GitScmPort {

  private static final String NO_QUOTE = "-c";
  private static final String QUOTE_PATH_OFF = "core.quotePath=false";
  private static final String LOG_FORMAT = "%x1e%H%x1f%aI%x1f%an";

  private final AnalysisProperties properties;

  @Override
  public Path clone(RepoUrl repoUrl, Path targetDirectory) {
    Path parent = targetDirectory.getParent();
    ensureEmptyDirectory(parent);
    try {
      run(parent, cloneArgs(repoUrl, targetDirectory));
      run(targetDirectory, NO_QUOTE, QUOTE_PATH_OFF, "read-tree", "HEAD");
      run(targetDirectory, NO_QUOTE, QUOTE_PATH_OFF, "checkout-index", "-a");
    } catch (GitCommandFailedException e) {
      ensureCloneable(repoUrl, e);
    }
    return targetDirectory;
  }

  @Override
  public List<String> trackedFiles(Path repository) {
    return splitNul(run(repository, NO_QUOTE, QUOTE_PATH_OFF, "ls-files", "-z"));
  }

  @Override
  public RepoChurn logNumstat(Path repository) {
    return parse(
        run(
            repository,
            NO_QUOTE,
            QUOTE_PATH_OFF,
            "log",
            "--no-merges",
            "--numstat",
            "--format=" + LOG_FORMAT,
            "--max-count=" + properties.getCommitWindow()));
  }

  @Override
  public String latestCommitSha(Path repository) {
    return run(repository, "rev-parse", "HEAD").strip();
  }

  protected String cloneUrl(RepoUrl repoUrl) {
    return "https://github.com/" + repoUrl.owner() + "/" + repoUrl.repo() + ".git";
  }

  private String[] cloneArgs(RepoUrl repoUrl, Path targetDirectory) {
    return new String[] {
      NO_QUOTE,
      QUOTE_PATH_OFF,
      "clone",
      "--no-checkout",
      "--no-recurse-submodules",
      "--depth",
      String.valueOf(properties.getCommitWindow()),
      cloneUrl(repoUrl),
      targetDirectory.getFileName().toString()
    };
  }

  private void ensureCloneable(RepoUrl repoUrl, GitCommandFailedException e) {
    String output = e.getOutput().toLowerCase();
    if (output.contains("not found") || output.contains("could not read username")) {
      throw new RepoNotFoundException("repo " + repoUrl + " not found or private");
    }
    throw e;
  }

  private String run(Path workingDirectory, String... args) {
    return GitProcessUtils.run(workingDirectory, properties.getGitCommandTimeout(), args);
  }

  private static List<String> splitNul(String output) {
    List<String> files = new ArrayList<>();
    for (String file : output.split("\u0000")) {
      if (!file.isEmpty()) {
        files.add(file);
      }
    }
    return files;
  }

  private static void ensureEmptyDirectory(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new GitCommandFailedException(
          "clone", "could not create directory " + directory + ": " + e.getMessage());
    }
    try (var entries = Files.list(directory)) {
      if (entries.findAny().isPresent()) {
        throw new GitCommandFailedException("clone", "target directory not empty: " + directory);
      }
    } catch (IOException e) {
      throw new GitCommandFailedException(
          "clone", "could not inspect directory " + directory + ": " + e.getMessage());
    }
  }
}
