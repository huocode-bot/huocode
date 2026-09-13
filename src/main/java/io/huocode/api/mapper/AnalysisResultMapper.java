package io.huocode.api.mapper;

import io.huocode.api.conf.AnalysisProperties;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.endpoint.rest.model.FileErrorCode;
import io.huocode.api.endpoint.rest.model.FileResult;
import io.huocode.api.endpoint.rest.model.FileResultChurn;
import io.huocode.api.endpoint.rest.model.FileStatus;
import io.huocode.api.endpoint.rest.model.Limitation;
import io.huocode.api.endpoint.rest.model.LimitationCode;
import io.huocode.api.endpoint.rest.model.Quadrant;
import io.huocode.api.endpoint.rest.model.QuadrantSummary;
import io.huocode.api.model.AnalysisWindowData;
import io.huocode.api.model.FileMeasurement;
import io.huocode.api.model.FileScore;
import io.huocode.api.model.LimitationDetail;
import io.huocode.api.model.QuadrantKind;
import io.huocode.api.model.RepoScore;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.model.RetrievalStrategy;
import io.huocode.api.scoring.ScoreEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnalysisResultMapper {

  private AnalysisResultMapper() {}

  public static AnalysisResult toAnalysisResult(
      RepoUrl repoUrl,
      String sha,
      RetrievalStrategy strategy,
      AnalysisWindowData window,
      Instant analyzedAt,
      RepoScore score,
      List<FileMeasurement> measurements,
      AnalysisProperties properties) {
    AnalysisResult result =
        new AnalysisResult()
            .status(AnalysisResult.StatusEnum.COMPLETED)
            .repo(repoUrl.toString())
            .commitSha(sha)
            .analyzedAt(analyzedAt)
            .strategy(AnalysisResult.StrategyEnum.valueOf(strategy.name()))
            .limitations(toLimitations(score.limitations()))
            .analysisWindow(toAnalysisWindow(window))
            .relativeScoringEnabled(score.relativeScoringEnabled())
            .scoreVersion(ScoreEngine.SCORE_VERSION)
            .top5(score.top5().stream().map(FileScore::path).toList())
            .files(toFileResults(measurements, score, properties));
    if (score.relativeScoringEnabled()) {
      result.repoHealthScore(score.repoHealthScore());
      result.summary(toSummary(score));
    }
    return result;
  }

  private static List<Limitation> toLimitations(List<LimitationDetail> limitations) {
    return limitations.stream()
        .map(
            limitation ->
                new Limitation()
                    .code(LimitationCode.valueOf(limitation.kind().name()))
                    .fileCount(limitation.fileCount()))
        .toList();
  }

  private static AnalysisWindow toAnalysisWindow(AnalysisWindowData window) {
    return new AnalysisWindow()
        .commitsAnalyzed(window.commitsAnalyzed())
        .oldestCommitDate(window.oldestCommitDate())
        .newestCommitDate(window.newestCommitDate())
        .distinctAuthors(window.distinctAuthors());
  }

  private static QuadrantSummary toSummary(RepoScore score) {
    Map<QuadrantKind, Integer> counts = score.quadrantCounts();
    return new QuadrantSummary()
        .hotspot(countOf(counts, QuadrantKind.HOTSPOT))
        .complexStable(countOf(counts, QuadrantKind.COMPLEX_STABLE))
        .frequentSimple(countOf(counts, QuadrantKind.FREQUENT_SIMPLE))
        .healthy(countOf(counts, QuadrantKind.HEALTHY))
        .unanalyzed(score.unanalyzedCount());
  }

  private static int countOf(Map<QuadrantKind, Integer> counts, QuadrantKind quadrant) {
    return counts.getOrDefault(quadrant, 0);
  }

  private static List<FileResult> toFileResults(
      List<FileMeasurement> measurements, RepoScore score, AnalysisProperties properties) {
    List<FileResult> results = new ArrayList<>(measurements.size());
    for (FileMeasurement measurement : measurements) {
      FileResult result =
          new FileResult()
              .path(measurement.path())
              .status(FileStatus.fromValue(measurement.status().name().toLowerCase()));
      switch (measurement.status()) {
        case ANALYZED:
          result
              .complexity(BigDecimal.valueOf(measurement.complexity()))
              .isTest(measurement.isTest())
              .churn(
                  new FileResultChurn()
                      .commits(measurement.churn().commits())
                      .authors(measurement.churn().authors())
                      .linesAdded(measurement.churn().linesAdded())
                      .linesDeleted(measurement.churn().linesDeleted())
                      .effectiveLines(measurement.churn().effectiveLines()))
              .exceedsCommonComplexityThreshold(
                  measurement.complexity() > properties.getExceedsCommonComplexityThreshold());
          if (score.relativeScoringEnabled()) {
            FileScore fileScore = score.scoresByPath().get(measurement.path());
            result
                .complexityPercentile(fileScore.complexityPercentile())
                .churnPercentile(fileScore.churnPercentile())
                .codeHealthScore(fileScore.codeHealthScore())
                .quadrant(Quadrant.fromValue(fileScore.quadrant().name().toLowerCase()));
          }
          break;
        case UNSUPPORTED_LANGUAGE:
          result.unsupportedLanguage(measurement.unsupportedLanguage());
          break;
        case ERROR:
          result.errorCode(FileErrorCode.valueOf(measurement.errorCode().name()));
          break;
      }
      results.add(result);
    }
    return results;
  }
}
