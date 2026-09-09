package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class ChallengeFailedException extends ApiException {

  public ChallengeFailedException(String message) {
    super(FailureCode.CHALLENGE_FAILED, HttpStatus.FORBIDDEN, message);
  }
}
