package io.huocode.api.conf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AnalysisController.class, ReportController.class})
class CorsConfigTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private AnalyzeRequestValidator requestValidator;
  @MockBean private AnalysisJobService analysisJobService;
  @MockBean private ErrorResponseMapper errorResponseMapper;
  @MockBean private RepoUrlAnalyzerService repoUrlAnalyzerService;

  @Test
  void no_cors_headers_when_no_origins_configured() throws Exception {
    mockMvc
        .perform(get("/report/owner/repo").header(HttpHeaders.ORIGIN, "https://vercel.app"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }
}
