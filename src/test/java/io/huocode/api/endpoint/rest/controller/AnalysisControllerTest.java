package io.huocode.api.endpoint.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalysisWindow;
import io.huocode.api.endpoint.rest.model.ErrorResponse;
import io.huocode.api.endpoint.rest.model.FailureCode;
import io.huocode.api.endpoint.rest.model.JobAccepted;
import io.huocode.api.endpoint.rest.model.JobFailed;
import io.huocode.api.endpoint.rest.model.JobProcessing;
import io.huocode.api.exception.JobNotFoundException;
import io.huocode.api.exception.RepoNotFoundException;
import io.huocode.api.exception.RepoTooLargeException;
import io.huocode.api.exception.RepoUrlValidationException;
import io.huocode.api.mapper.ErrorResponseMapper;
import io.huocode.api.model.RepoUrl;
import io.huocode.api.service.AnalysisJobService;
import io.huocode.api.validation.AnalyzeRequestValidator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
  @MockBean private AnalysisJobService analysisJobService;
  @MockBean private ErrorResponseMapper errorResponseMapper;

  private final RepoUrl repoUrl = new RepoUrl("owner", "repo");
  private final UUID jobId = UUID.randomUUID();
  private final String jobIdAsString = jobId.toString();

  @Test
  void analyze_returns_completed_result_for_sync_analysis() throws Exception {
    when(requestValidator.validate(any())).thenReturn(repoUrl);
    when(analysisJobService.submit(repoUrl))
        .thenReturn(AnalysisJobService.AnalysisSubmission.synchronous(aResult()));

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
  void analyze_returns_202_with_job_accepted_for_async_analysis() throws Exception {
    when(requestValidator.validate(any())).thenReturn(repoUrl);
    when(analysisJobService.submit(repoUrl))
        .thenReturn(
            AnalysisJobService.AnalysisSubmission.asynchronous(
                new JobAccepted()
                    .jobId(jobId)
                    .status(JobAccepted.StatusEnum.PROCESSING)
                    .estimatedSeconds(60)));

    mockMvc
        .perform(
            post("/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repoUrl\":\"https://github.com/owner/repo\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value(jobIdAsString))
        .andExpect(jsonPath("$.status").value("processing"))
        .andExpect(jsonPath("$.estimatedSeconds").value(60));
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
    when(analysisJobService.submit(repoUrl)).thenThrow(new RepoNotFoundException("repo not found"));
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
    when(analysisJobService.submit(repoUrl))
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

  @Test
  void get_job_returns_processing_shape() throws Exception {
    when(analysisJobService.getJob(jobId))
        .thenReturn(new JobProcessing().jobId(jobId).status(JobProcessing.StatusEnum.PROCESSING));

    mockMvc
        .perform(get("/analyze/{jobId}", jobIdAsString))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobIdAsString))
        .andExpect(jsonPath("$.status").value("processing"));
  }

  @Test
  void get_job_returns_failed_shape_with_error_code() throws Exception {
    when(analysisJobService.getJob(jobId))
        .thenReturn(
            new JobFailed()
                .jobId(jobId)
                .status(JobFailed.StatusEnum.FAILED)
                .errorCode(io.huocode.api.endpoint.rest.model.AsyncFailureCode.ANALYSIS_TIMEOUT));

    mockMvc
        .perform(get("/analyze/{jobId}", jobIdAsString))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobIdAsString))
        .andExpect(jsonPath("$.status").value("failed"))
        .andExpect(jsonPath("$.errorCode").value("ANALYSIS_TIMEOUT"));
  }

  @Test
  void get_job_returns_completed_result() throws Exception {
    when(analysisJobService.getJob(jobId)).thenReturn(aResult());

    mockMvc
        .perform(get("/analyze/{jobId}", jobIdAsString))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.repo").value("owner/repo"))
        .andExpect(jsonPath("$.repoHealthScore").value(78));
  }

  @Test
  void get_job_maps_unknown_job_to_404() throws Exception {
    when(analysisJobService.getJob(jobId)).thenThrow(new JobNotFoundException("no such job"));
    stubErrorMapping();

    mockMvc
        .perform(get("/analyze/{jobId}", jobIdAsString))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
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
