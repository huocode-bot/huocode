package io.huocode.api.adapter.git;

import static io.huocode.api.utils.ChurnParser.CommitChurn;
import static io.huocode.api.utils.ChurnParser.PathChange;

import io.huocode.api.exception.GitCommandFailedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
      Set<ObjectId> shallowBoundaries = repo.getObjectDatabase().getShallowCommits();
      int seen = 0;
      for (RevCommit commit : walk) {
        // A shallow-clone boundary looks parentless: diffing it against the empty tree would
        // count every file of the repository as fully added, so it is left out of the window.
        if (commit.getParentCount() > 1
            || shallowBoundaries.contains(commit)
            || hasUnavailableParent(walk, commit)) {
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
                pathChanges(repo, walk, commit)));
      }
      return commits;
    } catch (IOException e) {
      throw new GitCommandFailedException("log --numstat", e.getMessage());
    }
  }

  private static List<PathChange> pathChanges(Repository repo, RevWalk walk, RevCommit commit)
      throws IOException {
    List<PathChange> changes = new ArrayList<>();
    ObjectId parentTree = parentTreeOf(walk, commit);
    try (ObjectReader reader = repo.newObjectReader()) {
      CanonicalTreeParser newTree = new CanonicalTreeParser();
      newTree.reset(reader, commit.getTree());
      List<DiffEntry> entries;
      try (DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
        formatter.setRepository(repo);
        formatter.setContext(0);
        formatter.setDetectRenames(true);
        if (parentTree == null) {
          entries = formatter.scan(new EmptyTreeIterator(), newTree);
        } else {
          CanonicalTreeParser oldTree = new CanonicalTreeParser();
          oldTree.reset(reader, parentTree);
          entries = formatter.scan(oldTree, newTree);
        }
      }
      for (DiffEntry entry : entries) {
        changes.add(pathChange(repo, entry));
      }
    }
    return changes;
  }

  private static PathChange pathChange(Repository repo, DiffEntry entry) throws IOException {
    int added = 0;
    int deleted = 0;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffFormatter formatter = new DiffFormatter(out)) {
      formatter.setRepository(repo);
      formatter.setContext(0);
      formatter.format(entry);
      for (String line : out.toString(StandardCharsets.UTF_8).split("\\R")) {
        if (line.startsWith("+++") || line.startsWith("---")) {
          continue;
        }
        if (line.startsWith("+")) {
          if (isMeaningful(line.substring(1))) {
            added++;
          }
        } else if (line.startsWith("-")) {
          if (isMeaningful(line.substring(1))) {
            deleted++;
          }
        }
      }
    }
    String path = entry.getNewPath();
    if (entry.getChangeType() == DiffEntry.ChangeType.RENAME) {
      return new PathChange(path, entry.getOldPath(), added, deleted);
    }
    return new PathChange(
        DiffEntry.DEV_NULL.equals(path) ? entry.getOldPath() : path, added, deleted);
  }

  private static boolean isMeaningful(String content) {
    return !content.trim().isEmpty();
  }

  private static boolean hasUnavailableParent(RevWalk walk, RevCommit commit) throws IOException {
    for (RevCommit parent : commit.getParents()) {
      try {
        walk.parseHeaders(parent);
      } catch (MissingObjectException e) {
        return true;
      }
    }
    return false;
  }

  private static ObjectId parentTreeOf(RevWalk walk, RevCommit commit) throws IOException {
    if (commit.getParentCount() == 0) {
      return null;
    }
    return walk.parseCommit(commit.getParent(0)).getTree();
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
