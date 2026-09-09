package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class QueueFullException extends ApiException {

  public QueueFullException(String message, int retryAfterSeconds) {
    super(FailureCode.QUEUE_FULL, HttpStatus.SERVICE_UNAVAILABLE, message, retryAfterSeconds);
  }
}
