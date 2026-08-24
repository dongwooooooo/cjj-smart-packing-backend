package com.awesome.backend.outbound.controller;

import java.util.List;

/**
 * GET /api/v1/lines/{lineId}/shipments 응답 래퍼 — {@code {"shipments": [...]}}. docs/02-api-spec.md 3-1.
 */
public record LineShipmentListResponse(List<LineShipmentResponse> shipments) {
}
