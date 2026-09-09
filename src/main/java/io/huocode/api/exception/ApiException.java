package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

  private final FailureCode failureCode;
  private final HttpStatus httpStatus;

  public ApiException(FailureCode failureCode, HttpStatus httpStatus, String message) {
    super(message);
    this.failureCode = failureCode;
    this.httpStatus = httpStatus;
  }
}
