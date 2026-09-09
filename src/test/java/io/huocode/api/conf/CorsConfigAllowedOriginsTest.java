package io.huocode.api.conf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.huocode.api.endpoint.rest.controller.AnalysisController;
import io.huocode.api.endpoint.rest.controller.ReportController;
import io.huocode.api.mapper.ErrorResponseMapper;
import io.huocode.api.service.AnalysisJobService;
import io.huocode.api.service.RepoUrlAnalyzerService;
import io.huocode.api.validation.AnalyzeRequestValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AnalysisController.class, ReportController.class})
@TestPropertySource(
    properties = "huocode.cors.allowed-origins=https://allowed.example.com,https://*.vercel.app")
class CorsConfigAllowedOriginsTest {

  private static final String JOB_ID = "00000000-0000-0000-0000-000000000000";

  @Autowired private MockMvc mockMvc;

  @MockBean private AnalyzeRequestValidator requestValidator;
  @MockBean private AnalysisJobService analysisJobService;
  @MockBean private ErrorResponseMapper errorResponseMapper;
  @MockBean private RepoUrlAnalyzerService repoUrlAnalyzerService;

  @Test
  void preflight_returns_allow_origin_for_configured_origin() throws Exception {
    mockMvc
        .perform(
            options("/analyze")
                .header(HttpHeaders.ORIGIN, "https://allowed.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://allowed.example.com"));
  }

  @Test
  void actual_request_returns_allow_origin_for_configured_origin() throws Exception {
    mockMvc
        .perform(
            get("/analyze/" + JOB_ID).header(HttpHeaders.ORIGIN, "https://allowed.example.com"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://allowed.example.com"));
  }

  @Test
  void wildcard_preview_origin_is_allowed() throws Exception {
    mockMvc
        .perform(get("/report/owner/repo").header(HttpHeaders.ORIGIN, "https://abc.vercel.app"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://abc.vercel.app"));
  }

  @Test
  void examples_endpoint_returns_allow_origin_for_configured_origin() throws Exception {
    mockMvc
        .perform(get("/examples").header(HttpHeaders.ORIGIN, "https://allowed.example.com"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://allowed.example.com"));
  }

  @Test
  void unlisted_origin_is_rejected() throws Exception {
    mockMvc
        .perform(
            get("/report/owner/repo").header(HttpHeaders.ORIGIN, "https://unlisted.example.com"))
        .andExpect(status().isForbidden());
  }
}
