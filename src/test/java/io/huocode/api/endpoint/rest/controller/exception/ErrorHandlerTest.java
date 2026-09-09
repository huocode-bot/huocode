package io.huocode.api.endpoint.rest.controller.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.huocode.api.endpoint.rest.model.FailureCode;
import io.huocode.api.exception.RateLimitExceededException;
import io.huocode.api.exception.RepoUrlValidationException;
import io.huocode.api.mapper.ErrorResponseMapperImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class ErrorHandlerTest {

  private final ErrorHandler errorHandler = new ErrorHandler(new ErrorResponseMapperImpl());

  @Test
  void maps_api_exception_to_code_and_status() {
    var response = errorHandler.handleApiException(new RepoUrlValidationException("bad url"));
    assertSame(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertSame(FailureCode.INVALID_REPO_URL, response.getBody().getCode());
    assertEquals("bad url", response.getBody().getMessage());
  }

  @Test
  void rate_limited_exception_carries_retry_after_header() {
    var response =
        errorHandler.handleApiException(new RateLimitExceededException("slow down", 60));
    assertSame(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    assertEquals("60", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void api_exception_without_retry_after_has_no_retry_after_header() {
    var response = errorHandler.handleApiException(new RepoUrlValidationException("bad url"));
    assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void maps_unexpected_exception_to_internal_server_error() {
    var response = errorHandler.handleUnexpected(new RuntimeException("boom"));
    assertSame(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertNull(response.getBody().getCode());
    assertEquals("internal_error", response.getBody().getMessage());
  }
}
