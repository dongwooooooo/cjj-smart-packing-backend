package com.awesome.backend.outbound.controller;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/v1/shipments/{shipmentId}/box 요청 바디. docs/02-api-spec.md 3-3.
 */
public record BoxOverrideRequest(@NotNull(message = "boxTypeId는 필수입니다.") Long boxTypeId) {
}
