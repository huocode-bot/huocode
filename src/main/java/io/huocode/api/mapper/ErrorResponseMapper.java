package io.huocode.api.mapper;

import io.huocode.api.endpoint.rest.model.ErrorResponse;
import io.huocode.api.endpoint.rest.model.FailureCode;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ErrorResponseMapper {

  ErrorResponse toErrorResponse(FailureCode code, String message);
}
