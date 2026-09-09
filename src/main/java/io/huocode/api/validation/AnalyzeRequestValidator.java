package io.huocode.api.validation;

import io.huocode.api.endpoint.rest.model.AnalyzeRequest;
import io.huocode.api.exception.RepoUrlValidationException;
import io.huocode.api.model.RepoUrl;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class AnalyzeRequestValidator {

  private final RepoUrlValidator repoUrlValidator;

  public RepoUrl validate(AnalyzeRequest request) {
    if (request == null || request.getRepoUrl() == null) {
      throw new RepoUrlValidationException("request body must contain a valid repoUrl");
    }
    return repoUrlValidator.validate(request.getRepoUrl());
  }
}
