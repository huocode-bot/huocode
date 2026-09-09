package io.huocode.api.endpoint.rest.controller.exception;

import io.huocode.api.endpoint.rest.model.ErrorResponse;
import io.huocode.api.exception.ApiException;
import io.huocode.api.mapper.ErrorResponseMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@AllArgsConstructor
public class ErrorHandler {

  private final ErrorResponseMapper errorResponseMapper;

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
    HttpHeaders headers = new HttpHeaders();
    if (exception.getRetryAfterSeconds() != null) {
      headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()));
    }
    return ResponseEntity.status(exception.getHttpStatus())
        .headers(headers)
        .body(
            errorResponseMapper.toErrorResponse(
                exception.getFailureCode(), exception.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
    log.error("Unexpected error", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(errorResponseMapper.toErrorResponse(null, "internal_error"));
  }
}
