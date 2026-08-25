package com.awesome.backend.outbound.controller;

/**
 * PUT /api/v1/shipments/{shipmentId}/load 응답. docs/02-api-spec.md 3-9.
 *
 * <p>스펙 원문에 요청/응답 예시가 아예 없다 — 3-3 박스 오버라이드({@link BoxOverrideResponse})처럼
 * "바뀐 결과"만 최소한으로 돌려주는 스타일을 따른다. load()는 상태 전이가 전부라 바뀌는 값도
 * status 하나뿐이므로, 조회 대상 식별용 {@code shipmentId}와 전이 결과 {@code status} 두 필드로
 * 충분하다 — GET 3-2({@link ShipmentDetailResponse})처럼 전체 상세를 되돌려줄 이유가 없다.
 */
public record ShipmentLoadResponse(Long shipmentId, String status) {
}
