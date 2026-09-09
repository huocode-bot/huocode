package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class RepoNotFoundException extends ApiException {

  public RepoNotFoundException(String message) {
    super(FailureCode.REPO_NOT_FOUND_OR_PRIVATE, HttpStatus.NOT_FOUND, message);
  }
}
