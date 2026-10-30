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
  void score_computes_v13_scores_quadrants_and_excludes_tests_from_repo_health() {
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

    // Predictable with the v1.4 formula: activity is the double guard
    // (r >= mean + 2 sigma & effective lines >= 50) OR effective lines >= mean + 2 sigma.
    // complexity is the worst-method value (40 here) — a single god-method is flagged,
    // whatever the (simulated) number of small methods around it.
    // Percentiles are taken within the 19 production files (17 of them at 1 / 0): 17/19 -> 89.
    assertScore(score, "hot.java", 22, 100, 100, QuadrantKind.HOTSPOT);
    assertScore(score, "testHot.java", 22, 100, 100, QuadrantKind.HOTSPOT);
    assertScore(score, "onlyChurn.java", 55, 89, 100, QuadrantKind.FREQUENT_SIMPLE);
    assertScore(score, "onlyComplex.java", 67, 100, 89, QuadrantKind.COMPLEX_STABLE);
    assertScore(score, "base_1.java", 100, 89, 89, QuadrantKind.HEALTHY);

    // The activity penalty outweighs the complexity penalty: 55*cn <= 33 pts, 45*an <= 45 pts.
    assertTrue(scoreOf(score, "hot.java") < scoreOf(score, "onlyChurn.java"));
    assertTrue(scoreOf(score, "onlyChurn.java") < scoreOf(score, "onlyComplex.java"));

    // The summary covers production files only: testHot.java is scored but not counted.
    assertEquals(1, score.quadrantCounts().get(QuadrantKind.HOTSPOT));
    assertEquals(1, score.quadrantCounts().get(QuadrantKind.COMPLEX_STABLE));
    assertEquals(1, score.quadrantCounts().get(QuadrantKind.FREQUENT_SIMPLE));
    assertEquals(16, score.quadrantCounts().get(QuadrantKind.HEALTHY));

    // (19 non-test files: 16x100 + 22 + 67 + 55 = 1744 / 19 = 91.8 -> 92). testHot excluded.
    assertEquals(92, score.repoHealthScore());

    assertEquals(5, score.top5().size());
    assertEquals("hot.java", score.top5().get(0).path());
    assertTrue(score.top5().stream().noneMatch(file -> file.path().equals("testHot.java")));
    assertEquals("base_10.java", score.top5().get(4).path());
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

  @Test
  void repo_health_score_weights_files_by_lines_of_code() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 13; i++) {
      measurements.add(analyzed("tiny_" + i + ".java", 1, 0, 5, false));
    }
    measurements.add(analyzed("smallHealthy.java", 1, 0, 5, false));
    measurements.add(analyzed("largeUnhealthy.java", 50, 0, 1000, false));

    RepoScore score = engine.score(measurements);

    assertEquals(100, scoreOf(score, "smallHealthy.java"));
    assertEquals(67, scoreOf(score, "largeUnhealthy.java"));
    assertEquals(69, score.repoHealthScore());
  }

  @Test
  void repo_health_score_ignores_test_files_when_weighting() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 13; i++) {
      measurements.add(analyzed("tiny_" + i + ".java", 1, 0, 5, false));
    }
    measurements.add(analyzed("largeUnhealthy.java", 50, 0, 1000, false));
    measurements.add(analyzed("hugeTest.java", 50, 0, 100000, true));

    RepoScore score = engine.score(measurements);

    assertEquals(67, scoreOf(score, "hugeTest.java"));
    assertEquals(69, score.repoHealthScore());
  }

  @Test
  void activity_threshold_uses_the_sample_standard_deviation() {
    int[] churn = {4, 29, 37, 56, 81, 88, 109, 119, 128, 134, 136, 158, 170, 185, 235};
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 0; i < churn.length; i++) {
      measurements.add(analyzed("f" + i + ".java", 1, churn[i], 100, false));
    }

    RepoScore score = engine.score(measurements);

    for (int i = 0; i < churn.length; i++) {
      String path = "f" + i + ".java";
      assertEquals(100, scoreOf(score, path), () -> "score of " + path);
      assertEquals(
          QuadrantKind.HEALTHY,
          score.scoresByPath().get(path).quadrant(),
          () -> "quadrant of " + path);
    }
    assertEquals(100, score.repoHealthScore());
  }

  @Test
  void test_file_churn_does_not_move_the_production_activity_threshold() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      measurements.add(analyzed("f" + i + ".java", 1, 0, 100, false));
    }
    measurements.add(analyzed("prodHot.java", 1, 300, 100, false));
    // Heavily edited tests: counted in the population, they would push mean + 2 sigma above 300.
    for (int i = 0; i < 6; i++) {
      measurements.add(analyzed("t" + i + "Test.java", 1, 2000, 100, true));
    }

    RepoScore score = engine.score(measurements);

    assertEquals(QuadrantKind.FREQUENT_SIMPLE, score.scoresByPath().get("prodHot.java").quadrant());
    assertEquals(100, score.scoresByPath().get("prodHot.java").churnPercentile());
    assertEquals(QuadrantKind.FREQUENT_SIMPLE, score.scoresByPath().get("t0Test.java").quadrant());
    assertEquals(1, score.quadrantCounts().get(QuadrantKind.FREQUENT_SIMPLE));
    assertEquals("prodHot.java", score.top5().get(0).path());
  }

  @Test
  void a_tiny_file_with_an_extreme_ratio_does_not_cancel_the_penalty_of_a_large_active_file() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 14; i++) {
      measurements.add(analyzed("calm_" + i + ".java", 1, 0, 100, false));
    }
    // Ratio 150: the repo maximum.
    measurements.add(analyzed("tiny.java", 1, 300, 2, false));
    // Ratio 0.75 only, but 1500 effective lines: active by volume (threshold ~868).
    measurements.add(analyzed("large.java", 1, 1500, 2000, false));

    RepoScore score = engine.score(measurements);

    // Ratio-only normalization gave large.java an = log1p(0.75) / log1p(150) = 0.11, i.e. 95.
    assertScore(score, "large.java", 55, 100, 100, QuadrantKind.FREQUENT_SIMPLE);
    assertScore(score, "tiny.java", 55, 100, 94, QuadrantKind.FREQUENT_SIMPLE);
  }

  @Test
  void a_repo_made_only_of_test_files_is_summarized_from_those_files() {
    List<FileMeasurement> measurements = new ArrayList<>();
    for (int i = 1; i <= 15; i++) {
      measurements.add(analyzed("Calm" + i + "Test.java", 1, 0, 100, true));
    }
    measurements.add(analyzed("ComplexTest.java", 40, 0, 100, true));

    RepoScore score = engine.score(measurements);

    assertEquals(16, score.quadrantCounts().values().stream().mapToInt(Integer::intValue).sum());
    assertEquals("ComplexTest.java", score.top5().get(0).path());
    // 15 x 100 + 67, all LOC 100: 1567 / 16 = 97.9 -> 98.
    assertEquals(98, score.repoHealthScore());
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
