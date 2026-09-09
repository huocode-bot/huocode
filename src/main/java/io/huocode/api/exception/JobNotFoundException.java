package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class JobNotFoundException extends ApiException {

  public JobNotFoundException(String message) {
    super(FailureCode.JOB_NOT_FOUND, HttpStatus.NOT_FOUND, message);
  }
}
