package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ApiException {

  public RateLimitExceededException(String message) {
    super(FailureCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, message);
  }
}
