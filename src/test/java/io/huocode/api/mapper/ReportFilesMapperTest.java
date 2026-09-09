package io.huocode.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.endpoint.rest.model.FileResult;
import io.huocode.api.endpoint.rest.model.FileStatus;
import io.huocode.api.endpoint.rest.model.ListReportFiles200Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportFilesMapperTest {

  @Test
  void risk_sort_lists_worst_score_first() {
    ListReportFiles200Response response =
        ReportFilesMapper.slice(report(false), 1, 2, "risk", null);

    assertEquals(2, response.getFiles().size());
    assertEquals("b.java", response.getFiles().get(0).getPath());
    assertEquals("a.java", response.getFiles().get(1).getPath());
    assertEquals(4, response.getTotalFiles());
    assertTrue(response.getHasMore());
    assertEquals(1, response.getPage());
    assertEquals(2, response.getPageSize());
  }

  @Test
  void second_page_slices_correctly() {
    ListReportFiles200Response response =
        ReportFilesMapper.slice(report(false), 2, 2, "risk", null);

    assertEquals(2, response.getFiles().size());
    assertEquals("c.js", response.getFiles().get(0).getPath());
    assertFalse(response.getHasMore());
  }

  @Test
  void empty_page_beyond_last_one() {
    ListReportFiles200Response response =
        ReportFilesMapper.slice(report(false), 9, 2, "risk", null);

    assertEquals(0, response.getFiles().size());
    assertFalse(response.getHasMore());
    assertEquals(4, response.getTotalFiles());
  }

  @Test
  void path_sort_orders_alphabetically() {
    ListReportFiles200Response response =
        ReportFilesMapper.slice(report(false), 1, 10, "path", null);

    List<String> paths = response.getFiles().stream().map(FileResult::getPath).toList();
    assertEquals(List.of("a.java", "b.java", "c.js", "d.js"), paths);
  }

  @Test
  void status_filter_keeps_only_matching_files() {
    ListReportFiles200Response response =
        ReportFilesMapper.slice(report(false), 1, 10, "risk", FileStatus.ANALYZED);

    assertEquals(2, response.getTotalFiles());
    response.getFiles().forEach(file -> assertEquals(FileStatus.ANALYZED, file.getStatus()));
  }

  @Test
  void risk_sort_is_path_order_when_no_scores_are_present() {
    ListReportFiles200Response response =
        ReportFilesMapper.slice(report(true), 1, 10, "risk", null);

    List<String> paths = response.getFiles().stream().map(FileResult::getPath).toList();
    assertEquals(List.of("a.java", "b.java", "c.js", "d.js"), paths);
  }

  private AnalysisResult report(boolean relativeScoringDisabled) {
    FileResult d = new FileResult().path("d.js").status(FileStatus.UNSUPPORTED_LANGUAGE);
    FileResult c = new FileResult().path("c.js").status(FileStatus.UNSUPPORTED_LANGUAGE);
    if (relativeScoringDisabled) {
      return new AnalysisResult()
          .status(AnalysisResult.StatusEnum.COMPLETED)
          .repo("owner/repo")
          .commitSha("abc")
          .analyzedAt(Instant.now())
          .strategy(AnalysisResult.StrategyEnum.CLONE)
          .limitations(List.of())
          .analysisWindow(
              new AnalysisWindow()
                  .commitsAnalyzed(10)
                  .oldestCommitDate(Instant.now())
                  .newestCommitDate(Instant.now())
                  .distinctAuthors(2))
          .relativeScoringEnabled(false)
          .files(
              List.of(
                  new FileResult()
                      .path("a.java")
                      .status(FileStatus.ANALYZED)
                      .complexity(BigDecimal.valueOf(2)),
                  new FileResult()
                      .path("b.java")
                      .status(FileStatus.ANALYZED)
                      .complexity(BigDecimal.valueOf(3)),
                  c,
                  d))
          .top5(List.of());
    }
    return new AnalysisResult()
        .status(AnalysisResult.StatusEnum.COMPLETED)
        .repo("owner/repo")
        .commitSha("abc")
        .analyzedAt(Instant.now())
        .strategy(AnalysisResult.StrategyEnum.CLONE)
        .limitations(List.of())
        .analysisWindow(
            new AnalysisWindow()
                .commitsAnalyzed(10)
                .oldestCommitDate(Instant.now())
                .newestCommitDate(Instant.now())
                .distinctAuthors(2))
        .relativeScoringEnabled(true)
        .files(
            List.of(
                new FileResult()
                    .path("a.java")
                    .status(FileStatus.ANALYZED)
                    .complexity(BigDecimal.valueOf(2))
                    .codeHealthScore(90),
                new FileResult()
                    .path("b.java")
                    .status(FileStatus.ANALYZED)
                    .complexity(BigDecimal.valueOf(3))
                    .codeHealthScore(40),
                c,
                d))
        .top5(List.of());
  }
}
