package io.huocode.api.utils;

import static java.util.Arrays.copyOfRange;

import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

public final class ChurnParser {

  private static final Pattern BOT_PATTERN =
      Pattern.compile(".*\\[bot\\].*|^dependabot.*|^renovate.*", Pattern.CASE_INSENSITIVE);

  private ChurnParser() {}

  public static boolean isBot(String author) {
    return BOT_PATTERN.matcher(author).matches();
  }

  /** previousPath is the path before a rename in this commit, null otherwise. */
  public record PathChange(String path, String previousPath, int added, int deleted) {

    public PathChange(String path, int added, int deleted) {
      this(path, null, added, deleted);
    }
  }

  public record LineCounts(int added, int deleted) {}

  /**
   * Counts the non-blank added and deleted lines of a unified diff. Lines before the first hunk
   * header are file headers (---/+++), so a changed line such as "--i;" is still counted.
   */
  public static LineCounts countPatchLines(String patch) {
    int added = 0;
    int deleted = 0;
    boolean inHunk = false;
    for (String line : patch.split("\\R")) {
      if (line.startsWith("@@")) {
        inHunk = true;
      } else if (inHunk && line.startsWith("+") && isMeaningful(line.substring(1))) {
        added++;
      } else if (inHunk && line.startsWith("-") && isMeaningful(line.substring(1))) {
        deleted++;
      }
    }
    return new LineCounts(added, deleted);
  }

  /**
   * Counts the non-blank added and deleted lines between two versions of a file, with the diff
   * settings of the clone path (JGit histogram diff, default comparator). Binary content counts 0.
   */
  public static LineCounts countChangedLines(String before, String after) {
    byte[] beforeBytes = before.getBytes(StandardCharsets.UTF_8);
    byte[] afterBytes = after.getBytes(StandardCharsets.UTF_8);
    if (RawText.isBinary(beforeBytes) || RawText.isBinary(afterBytes)) {
      return new LineCounts(0, 0);
    }
    RawText a = new RawText(beforeBytes);
    RawText b = new RawText(afterBytes);
    int added = 0;
    int deleted = 0;
    for (Edit edit :
        DiffAlgorithm.getAlgorithm(SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, a, b)) {
      for (int line = edit.getBeginA(); line < edit.getEndA(); line++) {
        if (isMeaningful(a.getString(line))) {
          deleted++;
        }
      }
      for (int line = edit.getBeginB(); line < edit.getEndB(); line++) {
        if (isMeaningful(b.getString(line))) {
          added++;
        }
      }
    }
    return new LineCounts(added, deleted);
  }

  private static boolean isMeaningful(String content) {
    return !content.trim().isEmpty();
  }

  /**
   * Aggregates churn per path. A rename attributes the history of the previous path, in the commits
   * it descends from, to the path the file has today.
   */
  public static RepoChurn aggregate(List<CommitChurn> commits) {
    Map<String, Integer> commitsByPath = new HashMap<>();
    Map<String, Set<String>> authorsByPath = new HashMap<>();
    Map<String, Integer> addedByPath = new HashMap<>();
    Map<String, Integer> deletedByPath = new HashMap<>();
    List<Instant> dates = new ArrayList<>();
    Set<String> distinctAuthors = new HashSet<>();
    Map<String, String> currentPathOf = new HashMap<>();
    int commitsAnalyzed = 0;

    for (CommitChurn commit : childrenFirst(commits)) {
      if (commit.isMerge()) {
        continue;
      }
      Map<String, String> renames = new HashMap<>();
      for (PathChange change : commit.changes()) {
        String path = currentPathOf.getOrDefault(change.path(), change.path());
        if (change.previousPath() != null && !change.previousPath().equals(change.path())) {
          renames.put(change.previousPath(), path);
        }
      }
      if (!isBot(commit.author())) {
        commitsAnalyzed++;
        distinctAuthors.add(commit.author());
        if (commit.date() != null) {
          dates.add(commit.date());
        }
        for (PathChange change : commit.changes()) {
          String path = currentPathOf.getOrDefault(change.path(), change.path());
          commitsByPath.merge(path, 1, Integer::sum);
          authorsByPath.computeIfAbsent(path, ignored -> new HashSet<>()).add(commit.author());
          addedByPath.merge(path, change.added(), Integer::sum);
          deletedByPath.merge(path, change.deleted(), Integer::sum);
        }
      }
      // Registered after the commit itself: only its ancestors see the previous path.
      currentPathOf.putAll(renames);
    }
    return new RepoChurn(
        aggregateChurn(commitsByPath, authorsByPath, addedByPath, deletedByPath),
        window(commitsAnalyzed, dates, distinctAuthors));
  }

  /**
   * One commit of the window. id and parentIds order the commits by ancestry; a merge commit
   * (several parents) only carries ancestry: it is neither counted nor diffed.
   */
  public record CommitChurn(
      String id, List<String> parentIds, String author, Instant date, List<PathChange> changes) {

    public CommitChurn(String author, Instant date, List<PathChange> changes) {
      this(null, List.of(), author, date, changes);
    }

    public boolean isMerge() {
      return parentIds.size() > 1;
    }
  }

  /**
   * Orders commits so that every commit comes before its parents, whatever their timestamps (a
   * rebase or a skewed clock can date a parent after its child). Ties keep the input order.
   */
  static List<CommitChurn> childrenFirst(List<CommitChurn> commits) {
    Map<String, Integer> indexById = new HashMap<>();
    for (int i = 0; i < commits.size(); i++) {
      if (commits.get(i).id() != null) {
        indexById.put(commits.get(i).id(), i);
      }
    }
    int[] pendingChildren = new int[commits.size()];
    for (CommitChurn commit : commits) {
      for (String parent : commit.parentIds()) {
        Integer parentIndex = indexById.get(parent);
        if (parentIndex != null) {
          pendingChildren[parentIndex]++;
        }
      }
    }
    PriorityQueue<Integer> ready = new PriorityQueue<>();
    for (int i = 0; i < commits.size(); i++) {
      if (pendingChildren[i] == 0) {
        ready.add(i);
      }
    }
    List<CommitChurn> ordered = new ArrayList<>(commits.size());
    while (!ready.isEmpty()) {
      CommitChurn commit = commits.get(ready.poll());
      ordered.add(commit);
      for (String parent : commit.parentIds()) {
        Integer parentIndex = indexById.get(parent);
        if (parentIndex != null && --pendingChildren[parentIndex] == 0) {
          ready.add(parentIndex);
        }
      }
    }
    return ordered;
  }

  public static RepoChurn parse(String logOutput) {
    List<CommitChurn> commits = new ArrayList<>();
    for (String rawBlock : logOutput.split("\u001e")) {
      parseBlock(rawBlock).ifPresent(commits::add);
    }
    return aggregate(commits);
  }

  private static Optional<CommitChurn> parseBlock(String rawBlock) {
    if (rawBlock.isEmpty()) {
      return Optional.empty();
    }
    String[] lines = rawBlock.split("\\R");
    String[] header = lines[0].split("\u001f");
    if (header.length < 3) {
      return Optional.empty();
    }
    String author = header[2];
    if (isBot(author)) {
      return Optional.empty();
    }
    List<PathChange> changes = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      changeOf(lines[i]).ifPresent(changes::add);
    }
    return Optional.of(new CommitChurn(author, parseDate(header[1]).orElse(null), changes));
  }

  private static Optional<PathChange> changeOf(String numstatLine) {
    if (numstatLine.isEmpty()) {
      return Optional.empty();
    }
    String[] parts = numstatLine.split("\t", -1);
    if (parts.length < 3) {
      return Optional.empty();
    }
    return Optional.of(new PathChange(pathOf(parts), nonNegative(parts[0]), nonNegative(parts[1])));
  }

  private static int nonNegative(String value) {
    if ("-".equals(value)) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static Map<String, Churn> aggregateChurn(
      Map<String, Integer> commitsByPath,
      Map<String, Set<String>> authorsByPath,
      Map<String, Integer> addedByPath,
      Map<String, Integer> deletedByPath) {
    Map<String, Churn> result = new HashMap<>();
    commitsByPath.forEach(
        (path, commits) ->
            result.put(
                path,
                new Churn(
                    commits,
                    authorsByPath.get(path).size(),
                    addedByPath.getOrDefault(path, 0),
                    deletedByPath.getOrDefault(path, 0))));
    return result;
  }

  private static AnalysisWindowData window(
      int commitsAnalyzed, List<Instant> dates, Set<String> distinctAuthors) {
    Instant oldest = dates.stream().min(Instant::compareTo).orElse(null);
    Instant newest = dates.stream().max(Instant::compareTo).orElse(null);
    return new AnalysisWindowData(commitsAnalyzed, oldest, newest, distinctAuthors.size());
  }

  private static Optional<Instant> parseDate(String value) {
    if (value.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(value).toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String pathOf(String[] parts) {
    String path = String.join("\t", copyOfRange(parts, 2, parts.length));
    int arrow = path.indexOf(" => ");
    if (arrow >= 0) {
      path = path.substring(arrow + 4);
    }
    return path;
  }
}
