package io.huocode.api.endpoint.rest.controller;

import io.huocode.api.endpoint.rest.model.AnalyzeRequest;
import io.huocode.api.service.AnalysisJobService;
import io.huocode.api.validation.AnalyzeRequestValidator;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalysisController {

  private final AnalyzeRequestValidator requestValidator;
  private final AnalysisJobService analysisJobService;

  @PostMapping("/analyze")
  public ResponseEntity<Object> analyzeRepository(@RequestBody AnalyzeRequest request)
      throws IOException {
    AnalysisJobService.AnalysisSubmission submission =
        analysisJobService.submit(requestValidator.validate(request));
    if (submission.isAsync()) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission.jobAccepted());
    }
    return ResponseEntity.ok(submission.result());
  }

  @GetMapping("/analyze/{jobId}")
  public Object getAnalysisJob(@PathVariable UUID jobId) {
    return analysisJobService.getJob(jobId);
  }
}
