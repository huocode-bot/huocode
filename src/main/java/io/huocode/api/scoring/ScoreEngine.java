package io.huocode.api.scoring;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.FileMeasurement;
import io.huocode.api.model.FileScore;
import io.huocode.api.model.FileStatusKind;
import io.huocode.api.model.LimitationDetail;
import io.huocode.api.model.LimitationKind;
import io.huocode.api.model.QuadrantKind;
import io.huocode.api.model.RepoScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScoreEngine {

  private static final int HIGH_PERCENTILE = 70;

  private final AnalysisProperties properties;

  public RepoScore score(List<FileMeasurement> measurements) {
    List<FileMeasurement> analyzed =
        measurements.stream()
            .filter(measurement -> measurement.status() == FileStatusKind.ANALYZED)
            .toList();
    boolean relativeScoringEnabled = analyzed.size() >= properties.getMinFilesForRelativeScoring();
    List<LimitationDetail> limitations = new ArrayList<>();
    long unsupportedCount =
        measurements.stream()
            .filter(measurement -> measurement.status() == FileStatusKind.UNSUPPORTED_LANGUAGE)
            .count();
    if (unsupportedCount > 0) {
      limitations.add(
          new LimitationDetail(
              LimitationKind.UNSUPPORTED_LANGUAGE_FILES, Math.toIntExact(unsupportedCount)));
    }
    if (!relativeScoringEnabled) {
      limitations.add(
          new LimitationDetail(
              LimitationKind.INSUFFICIENT_FILES_FOR_RELATIVE_SCORING, analyzed.size()));
    }

    Map<String, FileScore> scoresByPath = new HashMap<>();
    Map<QuadrantKind, Integer> quadrantCounts = new EnumMap<>(QuadrantKind.class);
    List<FileScore> top5 = List.of();
    int repoHealthScore = 0;
    if (relativeScoringEnabled) {
      Map<String, Integer> complexityPercentiles =
          percentiles(analyzed, measurement -> measurement.complexity());
      Map<String, Integer> churnPercentiles =
          percentiles(analyzed, measurement -> measurement.churn().commits());
      for (FileMeasurement measurement : analyzed) {
        int complexityPercentile = complexityPercentiles.get(measurement.path());
        int churnPercentile = churnPercentiles.get(measurement.path());
        int codeHealthScore = Math.round(100 - (complexityPercentile + churnPercentile) / 2.0f);
        FileScore score =
            new FileScore(
                measurement.path(),
                complexityPercentile,
                churnPercentile,
                codeHealthScore,
                quadrant(complexityPercentile, churnPercentile));
        scoresByPath.put(measurement.path(), score);
      }
      quadrantCounts = new EnumMap<>(QuadrantKind.class);
      for (FileScore score : scoresByPath.values()) {
        quadrantCounts.merge(score.quadrant(), 1, Integer::sum);
      }
      repoHealthScore = averageHealth(scoresByPath.values());
      top5 = topScores(scoresByPath.values());
    }

    int unanalyzedCount = measurements.size() - analyzed.size();
    return new RepoScore(
        relativeScoringEnabled,
        repoHealthScore,
        quadrantCounts,
        unanalyzedCount,
        top5,
        limitations,
        scoresByPath);
  }

  private static Map<String, Integer> percentiles(
      List<FileMeasurement> analyzed, ToIntFunction<FileMeasurement> valueOf) {
    List<FileMeasurement> sorted = new ArrayList<>(analyzed);
    sorted.sort(Comparator.comparingInt(valueOf));
    Map<String, Integer> percentiles = new HashMap<>(analyzed.size());
    int size = sorted.size();
    int index = 0;
    while (index < size) {
      int value = valueOf.applyAsInt(sorted.get(index));
      int upperBound = index + 1;
      while (upperBound < size && valueOf.applyAsInt(sorted.get(upperBound)) == value) {
        upperBound++;
      }
      int percentile = Math.round(100.0f * upperBound / size);
      for (int i = index; i < upperBound; i++) {
        percentiles.put(sorted.get(i).path(), percentile);
      }
      index = upperBound;
    }
    return percentiles;
  }

  private static QuadrantKind quadrant(int complexityPercentile, int churnPercentile) {
    boolean complex = complexityPercentile >= HIGH_PERCENTILE;
    boolean churned = churnPercentile >= HIGH_PERCENTILE;
    if (complex && churned) {
      return QuadrantKind.HOTSPOT;
    }
    if (complex) {
      return QuadrantKind.COMPLEX_STABLE;
    }
    if (churned) {
      return QuadrantKind.FREQUENT_SIMPLE;
    }
    return QuadrantKind.HEALTHY;
  }

  private static int averageHealth(Iterable<FileScore> scores) {
    int sum = 0;
    int count = 0;
    for (FileScore score : scores) {
      sum += score.codeHealthScore();
      count++;
    }
    return Math.round((float) sum / count);
  }

  private static List<FileScore> topScores(Iterable<FileScore> scores) {
    List<FileScore> top = new ArrayList<>();
    scores.forEach(top::add);
    top.sort(
        Comparator.comparingInt(
                (FileScore score) -> score.complexityPercentile() + score.churnPercentile())
            .reversed()
            .thenComparing(FileScore::path));
    return top.size() <= 5 ? List.copyOf(top) : List.copyOf(top.subList(0, 5));
  }
}
