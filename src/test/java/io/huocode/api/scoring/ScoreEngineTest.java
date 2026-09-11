package io.huocode.api.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.model.Churn;
import io.huocode.api.model.FileMeasurement;
import io.huocode.api.model.FileStatusKind;
import io.huocode.api.model.LimitationKind;
import io.huocode.api.model.QuadrantKind;
import io.huocode.api.model.RepoScore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreEngineTest {

  private static final AnalysisProperties PROPERTIES =
      new AnalysisProperties(
          "",
          Duration.ofSeconds(5),
          300,
          20000,
          15,
          500,
          10,
          Duration.ofSeconds(10),
          1048576,
          Duration.ofHours(48),
          60,
          Duration.ofMinutes(15),
          10,
          60,
          2,
          10);

  private final ScoreEngine engine = new ScoreEngine(PROPERTIES);

  @Test
  void score_computes_percentiles_health_and_quadrants() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 15; i++) {
      measurements.add(analyzed("f" + i + ".java", i, i));
    }
    measurements.add(analyzed("hot.java", 1000, 1000));

    RepoScore score = engine.score(measurements);

    assertTrue(score.relativeScoringEnabled());
    assertTrue(score.limitations().isEmpty());
    assertEquals(16, score.scoresByPath().size());
    assertEquals(0, score.unanalyzedCount());

    assertEquals(100, score.scoresByPath().get("hot.java").complexityPercentile());
    assertEquals(100, score.scoresByPath().get("hot.java").churnPercentile());
    assertEquals(0, score.scoresByPath().get("hot.java").codeHealthScore());
    assertEquals(QuadrantKind.HOTSPOT, score.scoresByPath().get("hot.java").quadrant());

    assertEquals(94, score.scoresByPath().get("f15.java").complexityPercentile());
    assertEquals(12, score.scoresByPath().get("f15.java").codeHealthScore());
    assertEquals(QuadrantKind.HOTSPOT, score.scoresByPath().get("f15.java").quadrant());
    assertEquals(QuadrantKind.HEALTHY, score.scoresByPath().get("f1.java").quadrant());

    assertEquals(63, score.repoHealthScore());
    assertEquals(5, score.quadrantCounts().get(QuadrantKind.HOTSPOT));
    assertEquals(11, score.quadrantCounts().get(QuadrantKind.HEALTHY));
    assertEquals(5, score.top5().size());
    assertEquals("hot.java", score.top5().get(0).path());
  }

  @Test
  void score_disables_relative_scoring_below_threshold() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      measurements.add(analyzed("f" + i + ".java", i, i));
    }
    measurements.add(unsupported("script.py"));
    measurements.add(unsupported("app.js"));

    RepoScore score = engine.score(measurements);

    assertFalse(score.relativeScoringEnabled());
    assertTrue(score.scoresByPath().isEmpty());
    assertTrue(score.top5().isEmpty());
    assertTrue(score.quadrantCounts().isEmpty());
    assertEquals(0, score.repoHealthScore());
    assertEquals(2, score.unanalyzedCount());
    assertEquals(2, score.limitations().size());
    assertEquals(LimitationKind.UNSUPPORTED_LANGUAGE_FILES, score.limitations().get(0).kind());
    assertEquals(2, score.limitations().get(0).fileCount());
    assertEquals(
        LimitationKind.INSUFFICIENT_FILES_FOR_RELATIVE_SCORING, score.limitations().get(1).kind());
    assertEquals(10, score.limitations().get(1).fileCount());
  }

  @Test
  void score_gives_tied_values_the_same_percentile() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      measurements.add(analyzed("tiny" + i + ".java", 1, 1));
    }
    measurements.add(analyzed("big.java", 2, 2));

    RepoScore score = engine.score(measurements);

    for (int i = 0; i < 14; i++) {
      assertEquals(93, score.scoresByPath().get("tiny" + i + ".java").complexityPercentile());
    }
    assertEquals(100, score.scoresByPath().get("big.java").complexityPercentile());
    assertEquals(13, score.repoHealthScore());
  }

  @Test
  void score_combines_percentiles_as_a_product_not_a_sum_or_average() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      measurements.add(analyzed("c" + i + ".java", i, 1));
    }
    measurements.add(analyzed("c9.java", 9, 2));
    measurements.add(analyzed("c10.java", 10, 3));
    measurements.add(analyzed("c11.java", 11, 5));
    measurements.add(analyzed("c12.java", 12, 8));
    measurements.add(analyzed("c13.java", 13, 13));
    measurements.add(analyzed("complex.java", 900, 1));
    measurements.add(analyzed("churny.java", 1, 40));
    measurements.add(analyzed("hot.java", 1000, 1000));

    RepoScore score = engine.score(measurements);

    // A true hotspot (both signals high) is strictly the worst.
    assertEquals(0, score.scoresByPath().get("hot.java").codeHealthScore());
    assertEquals(QuadrantKind.HOTSPOT, score.scoresByPath().get("hot.java").quadrant());
    assertEquals("hot.java", score.top5().get(0).path());

    // High complexity alone (rarely changed) keeps a healthy score: the product
    // encodes the AND semantics, unlike a sum or average that a single high axis
    // would defeat.
    assertEquals(47, score.scoresByPath().get("complex.java").codeHealthScore());
    assertEquals(QuadrantKind.COMPLEX_STABLE, score.scoresByPath().get("complex.java").quadrant());
    assertTrue(
        score.scoresByPath().get("complex.java").codeHealthScore()
            > score.scoresByPath().get("hot.java").codeHealthScore());

    // High churn alone (very simple file) stays near the top of the range.
    assertEquals(88, score.scoresByPath().get("churny.java").codeHealthScore());
    assertEquals(QuadrantKind.FREQUENT_SIMPLE, score.scoresByPath().get("churny.java").quadrant());
    assertTrue(
        score.scoresByPath().get("churny.java").codeHealthScore()
            > score.scoresByPath().get("complex.java").codeHealthScore());

    assertEquals(23, score.scoresByPath().get("c13.java").codeHealthScore());
    assertEquals(93, score.scoresByPath().get("c1.java").codeHealthScore());
  }

  private static FileMeasurement analyzed(String path, int complexity, int churn) {
    return new FileMeasurement(
        path, FileStatusKind.ANALYZED, null, null, complexity, new Churn(churn, 1));
  }

  private static FileMeasurement unsupported(String path) {
    return new FileMeasurement(
        path, FileStatusKind.UNSUPPORTED_LANGUAGE, "python", null, 0, Churn.ZERO);
  }
}
