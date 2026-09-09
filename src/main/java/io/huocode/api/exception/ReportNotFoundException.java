package io.huocode.api.exception;

import io.huocode.api.endpoint.rest.model.FailureCode;
import org.springframework.http.HttpStatus;

public class ReportNotFoundException extends ApiException {

  public ReportNotFoundException(String message) {
    super(FailureCode.REPORT_NOT_FOUND, HttpStatus.NOT_FOUND, message);
  }
}
