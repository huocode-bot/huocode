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
  void score_computes_v12_scores_quadrants_and_excludes_tests_from_repo_health() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 16; i++) {
      // Never modified, 100-LOC file: truly healthy.
      measurements.add(analyzed("base_" + i + ".java", 1, 0, 100, false));
    }
    // Complex but never touched.
    measurements.add(analyzed("onlyComplex.java", 40, 0, 100, false));
    // High churn / LOC ratio on a simple file.
    measurements.add(analyzed("onlyChurn.java", 1, 900, 100, false));
    // Both signals.
    measurements.add(analyzed("hot.java", 40, 900, 100, false));
    // Same as hot.java but a test file: excluded from repo health score.
    measurements.add(analyzed("testHot.java", 40, 900, 100, true));

    RepoScore score = engine.score(measurements);

    assertTrue(score.relativeScoringEnabled());
    assertTrue(score.limitations().isEmpty());
    assertEquals(20, score.scoresByPath().size());
    assertEquals(0, score.unanalyzedCount());

    // Predictable with the v1.2 formula: activity is the double guard
    // (r >= mean + 2 sigma & effective lines >= 50) OR effective lines >= mean + 2 sigma.
    assertScore(score, "hot.java", 22, 100, 100, QuadrantKind.HOTSPOT);
    assertScore(score, "testHot.java", 22, 100, 100, QuadrantKind.HOTSPOT);
    assertScore(score, "onlyChurn.java", 55, 85, 100, QuadrantKind.FREQUENT_SIMPLE);
    assertScore(score, "onlyComplex.java", 67, 100, 85, QuadrantKind.COMPLEX_STABLE);
    assertScore(score, "base_1.java", 100, 85, 85, QuadrantKind.HEALTHY);

    // The activity penalty outweighs the complexity penalty: 55*cn <= 33 pts, 45*an <= 45 pts.
    assertTrue(scoreOf(score, "hot.java") < scoreOf(score, "onlyChurn.java"));
    assertTrue(scoreOf(score, "onlyChurn.java") < scoreOf(score, "onlyComplex.java"));

    assertEquals(2, score.quadrantCounts().get(QuadrantKind.HOTSPOT));
    assertEquals(1, score.quadrantCounts().get(QuadrantKind.COMPLEX_STABLE));
    assertEquals(1, score.quadrantCounts().get(QuadrantKind.FREQUENT_SIMPLE));
    assertEquals(16, score.quadrantCounts().get(QuadrantKind.HEALTHY));

    // (19 non-test files: 16x100 + 22 + 67 + 55 = 1744 / 19 = 91.8 -> 92). testHot excluded.
    assertEquals(92, score.repoHealthScore());

    assertEquals(5, score.top5().size());
    assertEquals("hot.java", score.top5().get(0).path());
    assertEquals("base_1.java", score.top5().get(4).path());
  }

  @Test
  void score_disables_relative_scoring_below_threshold() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      measurements.add(analyzed("f" + i + ".java", i, 0, 100, false));
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
      measurements.add(analyzed("tiny" + i + ".java", 1, 1, 100, false));
    }
    measurements.add(analyzed("big.java", 2, 2, 100, false));

    RepoScore score = engine.score(measurements);

    for (int i = 0; i < 14; i++) {
      assertEquals(93, score.scoresByPath().get("tiny" + i + ".java").complexityPercentile());
      assertEquals(93, score.scoresByPath().get("tiny" + i + ".java").churnPercentile());
    }
    assertEquals(100, score.scoresByPath().get("big.java").complexityPercentile());
    assertEquals(100, score.scoresByPath().get("big.java").churnPercentile());
    // big.java is the only active file (effective lines above mean + 2 sigma): 14x100 + 55 = 1455 /
    // 15 = 97.
    assertEquals(55, score.scoresByPath().get("big.java").codeHealthScore());
    assertEquals(QuadrantKind.FREQUENT_SIMPLE, score.scoresByPath().get("big.java").quadrant());
    assertEquals(97, score.repoHealthScore());
  }

  private static void assertScore(
      RepoScore score,
      String path,
      int health,
      int complexityPercentile,
      int churnPercentile,
      QuadrantKind quadrant) {
    assertEquals(
        health, score.scoresByPath().get(path).codeHealthScore(), () -> "score of " + path);
    assertEquals(
        complexityPercentile,
        score.scoresByPath().get(path).complexityPercentile(),
        () -> "cc of " + path);
    assertEquals(
        churnPercentile,
        score.scoresByPath().get(path).churnPercentile(),
        () -> "churn of " + path);
    assertEquals(quadrant, score.scoresByPath().get(path).quadrant(), () -> "quadrant of " + path);
  }

  private static int scoreOf(RepoScore score, String path) {
    return score.scoresByPath().get(path).codeHealthScore();
  }

  private static FileMeasurement analyzed(
      String path, int complexity, int effectiveLines, int linesOfCode, boolean isTest) {
    return new FileMeasurement(
        path,
        FileStatusKind.ANALYZED,
        null,
        null,
        complexity,
        new Churn(1, 1, effectiveLines, 0),
        isTest,
        linesOfCode);
  }

  private static FileMeasurement unsupported(String path) {
    return new FileMeasurement(
        path, FileStatusKind.UNSUPPORTED_LANGUAGE, "python", null, 0, Churn.ZERO, false, 0);
  }
}
