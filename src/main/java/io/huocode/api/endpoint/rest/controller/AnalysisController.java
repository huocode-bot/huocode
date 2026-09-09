package io.huocode.api.endpoint.rest.controller;

import io.huocode.api.endpoint.rest.model.AnalysisResult;
import io.huocode.api.endpoint.rest.model.AnalyzeRequest;
import io.huocode.api.service.RepoUrlAnalyzerService;
import io.huocode.api.validation.AnalyzeRequestValidator;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalysisController {

  private final AnalyzeRequestValidator requestValidator;
  private final RepoUrlAnalyzerService analyzerService;

  @PostMapping("/analyze")
  public AnalysisResult analyzeRepository(@RequestBody AnalyzeRequest request) throws IOException {
    return analyzerService.analyze(requestValidator.validate(request));
  }
}
