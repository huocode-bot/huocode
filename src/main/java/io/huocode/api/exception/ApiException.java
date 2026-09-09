package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

  private final FailureCode failureCode;
  private final HttpStatus httpStatus;
  private final Integer retryAfterSeconds;

  public ApiException(FailureCode failureCode, HttpStatus httpStatus, String message) {
    this(failureCode, httpStatus, message, null);
  }

  public ApiException(
      FailureCode failureCode, HttpStatus httpStatus, String message, Integer retryAfterSeconds) {
    super(message);
    this.failureCode = failureCode;
    this.httpStatus = httpStatus;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
