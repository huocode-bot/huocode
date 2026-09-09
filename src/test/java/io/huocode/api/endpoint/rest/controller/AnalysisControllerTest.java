package io.huocode.api.endpoint.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.endpoint.rest.model.ErrorResponse;
import io.huocode.api.endpoint.rest.model.FailureCode;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.exception.RepoTooLargeException;
import io.huocode.api.exception.RepoUrlValidationException;
import io.huocode.api.mapper.ErrorResponseMapper;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.service.RepoUrlAnalyzerService;
import io.huocode.api.validation.AnalyzeRequestValidator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private AnalyzeRequestValidator requestValidator;
  @MockBean private RepoUrlAnalyzerService analyzerService;
  @MockBean private ErrorResponseMapper errorResponseMapper;

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");

  @Test
  void analyze_returns_completed_result() throws Exception {
    when(requestValidator.validate(any())).thenReturn(repoUrl);
    when(analyzerService.analyze(repoUrl)).thenReturn(aResult());

    mockMvc
        .perform(
            post("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repoUrl\":\"https://github.com/owner/repo\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.repo").value("owner/repo"))
        .andExpect(jsonPath("$.strategy").value("clone"))
        .andExpect(jsonPath("$.relativeScoringEnabled").value(true))
        .andExpect(jsonPath("$.repoHealthScore").value(78));
  }

  @Test
  void analyze_maps_invalid_url_to_400() throws Exception {
    when(requestValidator.validate(any()))
        .thenThrow(new RepoUrlValidationException("repoUrl must strictly match"));
    stubErrorMapping();

    mockMvc
        .perform(
            post("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repoUrl\":\"ftp://elsewhere/repo\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPO_URL"));
  }

  @Test
  void analyze_maps_missing_repo_to_404() throws Exception {
    when(requestValidator.validate(any())).thenReturn(repoUrl);
    when(analyzerService.analyze(repoUrl)).thenThrow(new RepoNotFoundException("repo not found"));
    stubErrorMapping();

    mockMvc
        .perform(
            post("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repoUrl\":\"https://github.com/owner/repo\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPO_NOT_FOUND_OR_PRIVATE"));
  }

  @Test
  void analyze_maps_too_large_repo_to_413() throws Exception {
    when(requestValidator.validate(any())).thenReturn(repoUrl);
    when(analyzerService.analyze(repoUrl))
        .thenThrow(new RepoTooLargeException("repo has too many files"));
    stubErrorMapping();

    mockMvc
        .perform(
            post("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repoUrl\":\"https://github.com/owner/repo\"}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("REPO_TOO_LARGE"));
  }

  private void stubErrorMapping() {
    when(errorResponseMapper.toErrorResponse(any(), any()))
        .thenAnswer(
            invocation ->
                new ErrorResponse()
                    .code(invocation.getArgument(0, FailureCode.class))
                    .message(invocation.getArgument(1)));
  }

  private AnalysisResult aResult() {
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
        .files(List.of())
        .top5(List.of());
  }
}
