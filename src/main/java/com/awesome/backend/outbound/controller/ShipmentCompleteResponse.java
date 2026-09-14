package com.awesome.backend.outbound.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * POST /api/v1/shipments/{shipmentId}/complete 응답. docs/02-api-spec.md 3-8.
 *
 * <p>{@code expectedWeightKg}는 상품 무게와 박스 자체 무게로 구한 예상 총무게다. 상품 무게가
 * 하나라도 비어 있으면 null — 이때는 {@code measuredWeightKg}를 보내도 검수를 건너뛴다.
 *
 * <p>line은 {@code {lineId, packedCount}} 모양이라 3-2 LineResponse({@code {lineId, name}})와는
 * 다르다 — 재사용하지 않고 이 응답 전용 중첩 타입(Line)을 둔다.
 */
public record ShipmentCompleteResponse(
        Long shipmentId,
        String status,
        LocalDateTime packedAt,
        BigDecimal expectedWeightKg,
        Line line) {

    public record Line(Long lineId, long packedCount) {
    }
}
