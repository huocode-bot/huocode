package io.huocode.api.adapter.git;

import static io.huocode.api.utils.TimedGitCommand.run;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.exception.GitCommandFailedException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.model.RepoChurn;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.port.GitScmPort;
import io.huocode.api.utils.ChurnParser;
import io.huocode.api.utils.FilesUtils;
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

  private static final int MAX_RECLONES = 2;

  private final AnalysisProperties properties;

  @Override
  public Path clone(RepoUrl repoUrl, Path targetDirectory) {
    ensureEmptyDirectory(targetDirectory);
    run(timeout(), () -> cloneWindow(repoUrl, targetDirectory));
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
                new JGitChurnReader(properties.getCommitWindow()).read(repository).commits()));
  }

  @Override
  public String latestCommitSha(Path repository) {
    return run(timeout(), () -> headSha(repository));
  }

  protected String cloneUrl(RepoUrl repoUrl) {
    return "https://github.com/" + repoUrl.owner() + "/" + repoUrl.repo() + ".git";
  }

  /**
   * Merge commits use up clone depth without counting in the window, so a clone whose window comes
   * out short is redone twice as deep, until it holds commitWindow non-merge commits or the whole
   * history, like the window the GitHub API path reads. Re-clones are capped so a merge-heavy giant
   * cannot exhaust the git timeout: past the cap the window stays short, and commitsAnalyzed in the
   * result says so. JGit cannot deepen a shallow clone that contains merges (it drops the shallow
   * markers without fetching the missing commits), hence a fresh clone rather than a deepening
   * fetch.
   */
  private void cloneWindow(RepoUrl repoUrl, Path targetDirectory) {
    JGitChurnReader reader = new JGitChurnReader(properties.getCommitWindow());
    int depth = properties.getCommitWindow() + 1;
    cloneInto(repoUrl, targetDirectory, depth);
    JGitChurnReader.Window window = reader.scan(targetDirectory);
    for (int reclones = 0; window.truncated() && reclones < MAX_RECLONES; reclones++) {
      int commitsSeen = window.commits().size();
      depth *= 2;
      deleteClone(targetDirectory);
      cloneInto(repoUrl, targetDirectory, depth);
      window = reader.scan(targetDirectory);
      if (window.commits().size() <= commitsSeen) {
        break; // the remote ignored the depth: keep this clone rather than loop
      }
    }
  }

  private static void deleteClone(Path targetDirectory) {
    try {
      FilesUtils.deleteRecursively(targetDirectory);
    } catch (IOException e) {
      throw new GitCommandFailedException("clone", e.getMessage());
    }
  }

  private void cloneInto(RepoUrl repoUrl, Path targetDirectory, int depth) {
    String uri = cloneUrl(repoUrl);
    try {
      CloneCommand clone =
          Git.cloneRepository()
              .setURI(uri)
              .setDirectory(targetDirectory.toFile())
              .setCloneSubmodules(false);
      // One generation beyond the window so its oldest commit still has a parent to diff
      // against; the boundary commit itself is skipped by JGitChurnReader.
      clone.setDepth(depth);
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
