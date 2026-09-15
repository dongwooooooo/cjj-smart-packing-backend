package com.awesome.backend.outbound.controller;

import java.math.BigDecimal;

/**
 * POST /api/v1/shipments/{shipmentId}/complete 요청 본문 — 전부 선택 필드다.
 *
 * @param measuredWeightKg 포장을 마치고 저울에 올려 잰 무게. 보내면 예상 총무게와 대조하고,
 *                         허용 오차 밖이면 409 WEIGHT_MISMATCH 로 포장완료를 막는다.
 *                         보내지 않으면 검수를 건너뛴다 — 본문 없는 기존 호출이 그대로 돈다.
 */
public record ShipmentCompleteRequest(BigDecimal measuredWeightKg) {
}
