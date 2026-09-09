package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class ChallengeRequiredException extends ApiException {

  public ChallengeRequiredException(String message) {
    super(FailureCode.CHALLENGE_REQUIRED, HttpStatus.PRECONDITION_REQUIRED, message);
  }
}
