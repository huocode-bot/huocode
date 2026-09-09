package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class AnalysisTimeoutException extends ApiException {

  public AnalysisTimeoutException(String message) {
    super(FailureCode.ANALYSIS_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, message);
  }
}
