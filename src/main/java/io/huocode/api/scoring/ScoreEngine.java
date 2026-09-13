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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Score engine for scoring fiche v1.2 (docs/scoring-v1.2.md).
 *
 * <p>Activity is the relative code churn of CodeScene-style engineering standards: the ratio
 * effectiveLines / linesOfCode. A file is "active" when it is a double guard:
 *
 * <ul>
 *   <li>ratio r is above mean + 2 standard deviations AND effective lines >= MIN_EFFECTIVE_LINES,
 *       or
 *   <li>effective lines alone are above mean + 2 standard deviations.
 * </ul>
 *
 * <p>Score: round(max(20, 100 - 55*cn - 45*an)) where cn clamps at 0.6 and an at 1.0. Test files
 * (path under /test/ or *Test.java / *IT.java) are excluded from the repository health score.
 */
@Component
@RequiredArgsConstructor
public class ScoreEngine {

  public static final String SCORE_VERSION = "1.2";

  private static final int MIN_EFFECTIVE_LINES = 50;
  private static final double STDDEV_FACTOR = 2.0;
  private static final int MAX_COMPLEXITY_POINTS = 55;
  private static final int MAX_ACTIVITY_POINTS = 45;
  private static final int MIN_SCORE = 20;

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
          percentiles(analyzed, measurement -> measurement.churn().effectiveLines());
      Distribution churnDistribution =
          distribution(analyzed, measurement -> measurement.churn().effectiveLines());
      Distribution ratioDistribution = distribution(analyzed, ScoreEngine::ratioOf);
      double churnThreshold = churnDistribution.mean + STDDEV_FACTOR * churnDistribution.stdDev;
      double ratioThreshold = ratioDistribution.mean + STDDEV_FACTOR * ratioDistribution.stdDev;
      Set<String> testPaths = new HashSet<>();
      for (FileMeasurement measurement : analyzed) {
        if (measurement.isTest()) {
          testPaths.add(measurement.path());
        }
      }
      for (FileMeasurement measurement : analyzed) {
        boolean active = isActive(measurement, ratioThreshold, churnThreshold);
        boolean complex =
            isComplex(measurement.complexity(), properties.getExceedsCommonComplexityThreshold());
        double complexityPenalty =
            complex
                ? Math.min(
                    0.6,
                    (measurement.complexity() - properties.getExceedsCommonComplexityThreshold())
                        / 40.0)
                : 0.0;
        double activityPenalty =
            active && ratioDistribution.maxValue > 0
                ? Math.min(
                    1.0, Math.log1p(ratioOf(measurement)) / Math.log1p(ratioDistribution.maxValue))
                : 0.0;
        int codeHealthScore =
            (int)
                Math.round(
                    Math.max(
                        MIN_SCORE,
                        100
                            - MAX_COMPLEXITY_POINTS * complexityPenalty
                            - MAX_ACTIVITY_POINTS * activityPenalty));
        FileScore score =
            new FileScore(
                measurement.path(),
                complexityPercentiles.get(measurement.path()),
                churnPercentiles.get(measurement.path()),
                codeHealthScore,
                quadrant(active, complex));
        scoresByPath.put(measurement.path(), score);
      }
      quadrantCounts = new EnumMap<>(QuadrantKind.class);
      for (FileScore score : scoresByPath.values()) {
        quadrantCounts.merge(score.quadrant(), 1, Integer::sum);
      }
      repoHealthScore = averageHealth(scoresByPath.values(), testPaths);
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

  private static double ratioOf(FileMeasurement measurement) {
    int linesOfCode = measurement.linesOfCode();
    if (linesOfCode <= 0) {
      return 0.0;
    }
    return (double) measurement.churn().effectiveLines() / linesOfCode;
  }

  private static boolean isActive(
      FileMeasurement measurement, double ratioThreshold, double churnThreshold) {
    int effectiveLines = measurement.churn().effectiveLines();
    // Volume branch: effective lines alone above mean + 2 sigma (guarded > 0 to avoid a
    // degenerate all-zero-repository turning every file active).
    if (effectiveLines > 0 && effectiveLines >= churnThreshold) {
      return true;
    }
    // Ratio branch: relative churn above mean + 2 sigma AND a meaningful absolute floor.
    return effectiveLines >= MIN_EFFECTIVE_LINES && ratioOf(measurement) >= ratioThreshold;
  }

  private static boolean isComplex(int complexity, int exceedsCommonComplexityThreshold) {
    return complexity > exceedsCommonComplexityThreshold;
  }

  private static QuadrantKind quadrant(boolean active, boolean complex) {
    if (active && complex) {
      return QuadrantKind.HOTSPOT;
    }
    if (complex) {
      return QuadrantKind.COMPLEX_STABLE;
    }
    if (active) {
      return QuadrantKind.FREQUENT_SIMPLE;
    }
    return QuadrantKind.HEALTHY;
  }

  private static int averageHealth(Iterable<FileScore> scores, Set<String> excludedPaths) {
    int sum = 0;
    int count = 0;
    for (FileScore score : scores) {
      if (excludedPaths.contains(score.path())) {
        continue;
      }
      sum += score.codeHealthScore();
      count++;
    }
    return count == 0 ? 0 : Math.round((float) sum / count);
  }

  private static List<FileScore> topScores(Iterable<FileScore> scores) {
    List<FileScore> top = new ArrayList<>();
    scores.forEach(top::add);
    top.sort(Comparator.comparingInt(FileScore::codeHealthScore).thenComparing(FileScore::path));
    return top.size() <= 5 ? List.copyOf(top) : List.copyOf(top.subList(0, 5));
  }

  private record Distribution(double mean, double stdDev, double maxValue) {}

  private static Distribution distribution(
      List<FileMeasurement> analyzed, ToDoubleFunction<FileMeasurement> valueOf) {
    double sum = 0;
    double max = 0;
    for (FileMeasurement measurement : analyzed) {
      double value = valueOf.applyAsDouble(measurement);
      sum += value;
      max = Math.max(max, value);
    }
    double mean = sum / analyzed.size();
    double squaredDeviationSum = 0;
    for (FileMeasurement measurement : analyzed) {
      double deviation = valueOf.applyAsDouble(measurement) - mean;
      squaredDeviationSum += deviation * deviation;
    }
    return new Distribution(mean, Math.sqrt(squaredDeviationSum / analyzed.size()), max);
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
}
