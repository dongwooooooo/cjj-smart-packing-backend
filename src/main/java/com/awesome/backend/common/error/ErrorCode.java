package com.awesome.backend.common.error;

import org.springframework.http.HttpStatus;

/**
 * Error codes defined in docs/02-api-spec.md §0.
 * MEASURE_FAILED is a measurement response status, not an error — excluded here.
 */
public enum ErrorCode {

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    GATE_NOT_PASSED(HttpStatus.CONFLICT),
    SESSION_ALREADY_CONFIRMED(HttpStatus.CONFLICT),
    TOTE_NOT_ASSIGNED(HttpStatus.NOT_FOUND),
    SHIPMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    BOX_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_STATE(HttpStatus.CONFLICT),
    OUT_OF_STOCK(HttpStatus.CONFLICT),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
