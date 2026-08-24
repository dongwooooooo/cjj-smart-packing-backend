package com.awesome.backend.inbound.controller;

import jakarta.validation.constraints.NotNull;

/** 촬영·추론 요청 (02 §1-3). */
public record MeasurementRequest(
        @NotNull(message = "productId 는 필수입니다.")
        Long productId) {
}
