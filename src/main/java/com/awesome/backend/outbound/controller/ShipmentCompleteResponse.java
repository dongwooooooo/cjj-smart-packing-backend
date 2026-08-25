package com.awesome.backend.outbound.controller;

import java.time.LocalDateTime;

/**
 * POST /api/v1/shipments/{shipmentId}/complete 응답. docs/02-api-spec.md 3-8.
 *
 * <p>line은 {@code {lineId, packedCount}} 모양이라 3-2 LineResponse({@code {lineId, name}})와는
 * 다르다 — 재사용하지 않고 이 응답 전용 중첩 타입(Line)을 둔다.
 */
public record ShipmentCompleteResponse(
        Long shipmentId,
        String status,
        LocalDateTime packedAt,
        Line line) {

    public record Line(Long lineId, long packedCount) {
    }
}
