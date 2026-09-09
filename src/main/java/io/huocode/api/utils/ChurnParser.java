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

  public static RepoChurn parse(String logOutput) {
    Map<String, Integer> commitsByPath = new HashMap<>();
    Map<String, Set<String>> authorsByPath = new HashMap<>();
    List<Instant> dates = new ArrayList<>();
    Set<String> distinctAuthors = new HashSet<>();
    int commitsAnalyzed = 0;

    for (String rawBlock : logOutput.split("\u001e")) {
      Optional<Block> parsed = parseBlock(rawBlock);
      if (parsed.isEmpty()) {
        continue;
      }
      Block block = parsed.get();
      commitsAnalyzed++;
      distinctAuthors.add(block.author());
      block.date().ifPresent(dates::add);
      for (String path : block.paths()) {
        commitsByPath.merge(path, 1, Integer::sum);
        authorsByPath.computeIfAbsent(path, ignored -> new HashSet<>()).add(block.author());
      }
    }
    return new RepoChurn(
        aggregateChurn(commitsByPath, authorsByPath),
        window(commitsAnalyzed, dates, distinctAuthors));
  }

  private static Optional<Block> parseBlock(String rawBlock) {
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
    List<String> paths = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      String path = pathOf(lines[i]);
      if (path != null) {
        paths.add(path);
      }
    }
    return Optional.of(new Block(author, parseDate(header[1]), paths));
  }

  private static Map<String, Churn> aggregateChurn(
      Map<String, Integer> commitsByPath, Map<String, Set<String>> authorsByPath) {
    Map<String, Churn> result = new HashMap<>();
    commitsByPath.forEach(
        (path, commits) -> result.put(path, new Churn(commits, authorsByPath.get(path).size())));
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

  private static String pathOf(String numstatLine) {
    if (numstatLine.isEmpty()) {
      return null;
    }
    String[] parts = numstatLine.split("\t", -1);
    if (parts.length < 3) {
      return null;
    }
    String path = String.join("\t", copyOfRange(parts, 2, parts.length));
    int arrow = path.indexOf(" => ");
    if (arrow >= 0) {
      path = path.substring(arrow + 4);
    }
    return path;
  }

  private record Block(String author, Optional<Instant> date, List<String> paths) {}
}
