package io.huocode.api.utils;

import static java.util.Arrays.copyOfRange;

import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.Churn;
import io.huocode.api.model.RepoChurn;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class ChurnParser {

  private static final Pattern BOT_PATTERN =
      Pattern.compile(".*\\[bot\\].*|^dependabot.*|^renovate.*", Pattern.CASE_INSENSITIVE);

  private ChurnParser() {}

  public static boolean isBot(String author) {
    return BOT_PATTERN.matcher(author).matches();
  }

  public record PathChange(String path, int added, int deleted) {}

  public static RepoChurn aggregate(List<CommitChurn> commits) {
    Map<String, Integer> commitsByPath = new HashMap<>();
    Map<String, Set<String>> authorsByPath = new HashMap<>();
    Map<String, Integer> addedByPath = new HashMap<>();
    Map<String, Integer> deletedByPath = new HashMap<>();
    List<Instant> dates = new ArrayList<>();
    Set<String> distinctAuthors = new HashSet<>();
    int commitsAnalyzed = 0;

    for (CommitChurn commit : commits) {
      if (isBot(commit.author())) {
        continue;
      }
      commitsAnalyzed++;
      distinctAuthors.add(commit.author());
      if (commit.date() != null) {
        dates.add(commit.date());
      }
      for (PathChange change : commit.changes()) {
        commitsByPath.merge(change.path(), 1, Integer::sum);
        authorsByPath
            .computeIfAbsent(change.path(), ignored -> new HashSet<>())
            .add(commit.author());
        addedByPath.merge(change.path(), change.added(), Integer::sum);
        deletedByPath.merge(change.path(), change.deleted(), Integer::sum);
      }
    }
    return new RepoChurn(
        aggregateChurn(commitsByPath, authorsByPath, addedByPath, deletedByPath),
        window(commitsAnalyzed, dates, distinctAuthors));
  }

  public record CommitChurn(String author, Instant date, List<PathChange> changes) {}

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
