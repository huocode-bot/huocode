package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class GitHubRateLimitReachedException extends ApiException {

  public GitHubRateLimitReachedException(String message) {
    super(FailureCode.GITHUB_RATE_LIMIT_REACHED, HttpStatus.TOO_MANY_REQUESTS, message);
  }
}
