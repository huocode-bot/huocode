package io.huocode.api.adapter.git;

import static io.huocode.api.utils.TimedGitCommand.run;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitCommandFailedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitScmPort;
import io.huocode.api.utils.ChurnParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JGitScmAdapter implements GitScmPort {

  private final AnalysisProperties properties;

  @Override
  public Path clone(RepoUrl repoUrl, Path targetDirectory) {
    ensureEmptyDirectory(targetDirectory);
    run(timeout(), () -> cloneInto(repoUrl, targetDirectory));
    return targetDirectory;
  }

  @Override
  public List<String> trackedFiles(Path repository) {
    return run(timeout(), () -> trackedFilesNow(repository));
  }

  @Override
  public RepoChurn logNumstat(Path repository) {
    return run(
        timeout(),
        () ->
            ChurnParser.aggregate(
                new JGitChurnReader(properties.getCommitWindow()).read(repository)));
  }

  @Override
  public String latestCommitSha(Path repository) {
    return run(timeout(), () -> headSha(repository));
  }

  protected String cloneUrl(RepoUrl repoUrl) {
    return "https://github.com/" + repoUrl.owner() + "/" + repoUrl.repo() + ".git";
  }

  private void cloneInto(RepoUrl repoUrl, Path targetDirectory) {
    String uri = cloneUrl(repoUrl);
    try {
      CloneCommand clone =
          Git.cloneRepository()
              .setURI(uri)
              .setDirectory(targetDirectory.toFile())
              .setCloneSubmodules(false);
      int depth = depthFor(uri);
      if (depth > 0) {
        clone.setDepth(depth);
      }
      try (Git ignored = clone.call()) {}
    } catch (GitAPIException e) {
      throw asCloneFailure(uri, e);
    }
  }

  private static List<String> trackedFilesNow(Path repository) throws IOException {
    try (Repository repo = open(repository);
        RevWalk walk = new RevWalk(repo);
        TreeWalk tree = new TreeWalk(repo)) {
      tree.addTree(walk.parseCommit(headOf(repo)).getTree());
      tree.setRecursive(true);
      List<String> files = new ArrayList<>();
      while (tree.next()) {
        files.add(tree.getPathString());
      }
      Collections.sort(files);
      return files;
    }
  }

  private static String headSha(Path repository) throws IOException {
    try (Repository repo = open(repository)) {
      return headOf(repo).name();
    }
  }

  private static ObjectId headOf(Repository repo) throws IOException {
    ObjectId head = repo.resolve("HEAD");
    if (head == null) {
      throw new GitCommandFailedException("git", "no HEAD in " + repo.getDirectory());
    }
    return head;
  }

  private static Repository open(Path repository) throws IOException {
    return new FileRepositoryBuilder().setGitDir(repository.resolve(".git").toFile()).build();
  }

  private int depthFor(String uri) {
    return uri.startsWith("file:") ? 0 : properties.getCommitWindow();
  }

  private static void ensureEmptyDirectory(Path targetDirectory) {
    try {
      if (Files.exists(targetDirectory) && Files.list(targetDirectory).findAny().isPresent()) {
        throw new GitCommandFailedException(
            "clone", "directory " + targetDirectory + " is not empty");
      }
      Files.createDirectories(targetDirectory);
    } catch (IOException e) {
      throw new GitCommandFailedException("clone", e.getMessage());
    }
  }

  private static RuntimeException asCloneFailure(String uri, Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    if (message.contains("not found") || message.contains("Not Found")) {
      return new RepoNotFoundException(uri);
    }
    return new GitCommandFailedException("clone " + uri, message);
  }

  private Duration timeout() {
    return properties.getGitCommandTimeout();
  }
}
