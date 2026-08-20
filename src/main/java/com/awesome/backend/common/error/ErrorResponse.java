package com.awesome.backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Common error body (docs/02-api-spec.md §0):
 * { "code": "...", "message": "...", "detail": { ... } }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, Object> detail) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> detail) {
        return new ErrorResponse(code.name(), message, detail);
    }
}
