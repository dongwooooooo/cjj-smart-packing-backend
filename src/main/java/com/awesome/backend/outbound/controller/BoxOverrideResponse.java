package com.awesome.backend.outbound.controller;

/**
 * PUT /api/v1/shipments/{shipmentId}/box 응답. docs/02-api-spec.md 3-3.
 *
 * <p>{@code recommendedBoxId}는 오버라이드로 바뀌지 않는 원래 추천값을 그대로 되돌려준다 —
 * {@code recommendedBoxId != finalBoxId}로 오버라이드 여부를 보존한다(재검수 큐 연동은 보류, 3-3 하단 참고).
 */
public record BoxOverrideResponse(Long shipmentId, Long recommendedBoxId, Long finalBoxId) {
}
