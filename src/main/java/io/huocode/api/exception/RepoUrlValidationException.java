package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class RepoUrlValidationException extends ApiException {

  public RepoUrlValidationException(String message) {
    super(FailureCode.INVALID_REPO_URL, HttpStatus.BAD_REQUEST, message);
  }
}
