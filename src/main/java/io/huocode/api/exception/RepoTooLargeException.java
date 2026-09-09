package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class RepoTooLargeException extends ApiException {

  public RepoTooLargeException(String message) {
    super(FailureCode.REPO_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, message);
  }
}
