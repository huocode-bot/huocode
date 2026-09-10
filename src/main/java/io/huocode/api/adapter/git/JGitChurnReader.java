package io.huocode.api.adapter.git;

import io.huocode.api.exception.GitCommandFailedException;
import io.huocode.api.utils.ChurnParser.CommitChurn;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

public class JGitChurnReader {

  private final int window;

  public JGitChurnReader(int window) {
    this.window = window;
  }

  public List<CommitChurn> read(Path repository) {
    List<CommitChurn> commits = new ArrayList<>();
    try (Repository repo = open(repository);
        RevWalk walk = new RevWalk(repo)) {
      walk.sort(RevSort.COMMIT_TIME_DESC);
      walk.markStart(walk.parseCommit(headOf(repo)));
      int seen = 0;
      for (RevCommit commit : walk) {
        if (commit.getParentCount() > 1) {
          continue;
        }
        if (seen >= window) {
          break;
        }
        seen++;
        commits.add(
            new CommitChurn(
                commit.getAuthorIdent().getName(),
                commit.getAuthorIdent().getWhen().toInstant(),
                changedPaths(repo, walk, commit)));
      }
      return commits;
    } catch (IOException e) {
      throw new GitCommandFailedException("log --numstat", e.getMessage());
    }
  }

  private static List<String> changedPaths(Repository repo, RevWalk walk, RevCommit commit)
      throws IOException {
    List<String> paths = new ArrayList<>();
    ObjectId parentTree = parentTreeOf(walk, commit);
    try (ObjectReader reader = repo.newObjectReader();
        DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
      formatter.setRepository(repo);
      formatter.setContext(0);
      CanonicalTreeParser newTree = new CanonicalTreeParser();
      newTree.reset(reader, commit.getTree());
      List<DiffEntry> entries;
      if (parentTree == null) {
        entries = formatter.scan(new EmptyTreeIterator(), newTree);
      } else {
        CanonicalTreeParser oldTree = new CanonicalTreeParser();
        oldTree.reset(reader, parentTree);
        entries = formatter.scan(oldTree, newTree);
      }
      for (DiffEntry entry : entries) {
        String path = entry.getNewPath();
        paths.add(DiffEntry.DEV_NULL.equals(path) ? entry.getOldPath() : path);
      }
    }
    return paths;
  }

  private static ObjectId parentTreeOf(RevWalk walk, RevCommit commit) {
    if (commit.getParentCount() == 0) {
      return null;
    }
    try {
      return walk.parseCommit(commit.getParent(0)).getTree();
    } catch (MissingObjectException e) {
      return null;
    } catch (IOException e) {
      throw new GitCommandFailedException("log --numstat", e.getMessage());
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
}
