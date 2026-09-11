package io.huocode.api.endpoint.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.huocode.api.conf.ExamplesConfig;
import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.endpoint.rest.model.ErrorResponse;
import io.huocode.api.endpoint.rest.model.FailureCode;
import io.huocode.api.endpoint.rest.model.FileResult;
import io.huocode.api.endpoint.rest.model.FileStatus;
import io.huocode.api.exception.ReportNotFoundException;
import io.huocode.api.mapper.ErrorResponseMapper;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.service.RepoUrlAnalyzerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReportController.class)
@Import(ExamplesConfig.class)
@TestPropertySource(properties = "huocode.examples=foo/bar,baz/qux")
class ReportControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private RepoUrlAnalyzerService analyzerService;
  @MockBean private ErrorResponseMapper errorResponseMapper;

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");

  @Test
  void latest_report_returns_full_result() throws Exception {
    when(analyzerService.getLatestReport(repoUrl)).thenReturn(aResult());

    mockMvc
        .perform(get("/report/owner/repo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repo").value("owner/repo"))
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.relativeScoringEnabled").value(true));
  }

  @Test
  void missing_report_maps_to_404() throws Exception {
    when(analyzerService.getLatestReport(repoUrl))
        .thenThrow(new ReportNotFoundException("no cached result for owner/repo"));
    when(errorResponseMapper.toErrorResponse(any(), any()))
        .thenAnswer(
            invocation ->
                new ErrorResponse()
                    .code(invocation.getArgument(0, FailureCode.class))
                    .message(invocation.getArgument(1)));

    mockMvc
        .perform(get("/report/owner/repo"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
  }

  @Test
  void files_list_supports_risk_sort_and_pagination() throws Exception {
    when(analyzerService.getLatestReport(repoUrl)).thenReturn(aResult());

    mockMvc
        .perform(get("/report/owner/repo/files").param("page", "1").param("pageSize", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.pageSize").value(1))
        .andExpect(jsonPath("$.totalFiles").value(3))
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.files[0].path").value("b.java"));
  }

  @Test
  void files_list_filters_by_status() throws Exception {
    when(analyzerService.getLatestReport(repoUrl)).thenReturn(aResult());

    mockMvc
        .perform(get("/report/owner/repo/files").param("status", "unsupported_language"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalFiles").value(1))
        .andExpect(jsonPath("$.files[0].status").value("unsupported_language"));
  }

  @Test
  void examples_returns_configured_repos() throws Exception {
    mockMvc
        .perform(get("/examples"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].repo").value("foo/bar"))
        .andExpect(jsonPath("$[0].repoUrl").value("https://github.com/foo/bar"))
        .andExpect(jsonPath("$[1].repo").value("baz/qux"));
  }

  private AnalysisResult aResult() {
    FileResult worst =
        new FileResult()
            .path("b.java")
            .status(FileStatus.ANALYZED)
            .complexity(BigDecimal.valueOf(30))
            .codeHealthScore(10);
    FileResult best =
        new FileResult()
            .path("a.java")
            .status(FileStatus.ANALYZED)
            .complexity(BigDecimal.valueOf(5))
            .codeHealthScore(90);
    FileResult unsupported = new FileResult().path("c.js").status(FileStatus.UNSUPPORTED_LANGUAGE);
    return new AnalysisResult()
        .status(AnalysisResult.StatusEnum.COMPLETED)
        .repo(repoUrl.toString())
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
        .repoHealthScore(78)
        .files(List.of(worst, best, unsupported))
        .top5(List.of("b.java"));
  }
}
