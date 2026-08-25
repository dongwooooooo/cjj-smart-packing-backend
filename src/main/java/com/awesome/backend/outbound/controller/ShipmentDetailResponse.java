package com.awesome.backend.outbound.controller;

import java.util.List;

/**
 * GET /api/v1/shipments/{shipmentId} 응답. docs/02-api-spec.md 3-2 — 박스 추천 화면의 핵심 API.
 *
 * <p>최상위 레코드와 중첩 타입(LineResponse/ToteResponse/ItemResponse)을 한 파일에 몰아 담았다 —
 * 셋 다 이 응답 하나를 구성하는 조각일 뿐 다른 API에서 재사용되지 않아, 별도 파일로 쪼갤 만큼
 * 독립적이지 않다. {@code recommendedBox}/{@code finalBox}는 GET /box-types 응답과 필드 모양이
 * 정확히 같아 새 DTO를 만들지 않고 {@link BoxTypeResponse}를 그대로 재사용한다.
 *
 * <p>{@code tote}/{@code finalBox}는 nullable — 활성 tote_assignment가 없으면 {@code tote}가,
 * {@code Shipment.finalBoxId}가 없으면 {@code finalBox}가 필드째로 {@code null}이 된다(둘 다
 * 기본 Jackson 직렬화 그대로 {@code null}을 출력하므로 별도 {@code @JsonInclude} 설정은 필요 없다).
 */
public record ShipmentDetailResponse(
        Long shipmentId,
        Long orderId,
        int seqNo,
        String status,
        LineResponse line,
        ToteResponse tote,
        BoxTypeResponse recommendedBox,
        BoxTypeResponse finalBox,
        boolean fillerRecommended,
        List<ItemResponse> items) {

    public record LineResponse(Long lineId, String name) {
    }

    public record ToteResponse(Long toteId, String barcode) {
    }

    /**
     * @param handling 파생 취급속성(docs/03-erd.md §6) — 저장되지 않고 조회 시 계산된다.
     *                 REFRIGERATE(냉장 필수) / LIQUID_CAUTION(액체주의) / IRREGULAR(적층불가).
     *                 해당 없는 속성은 배열에서 빠진다(전부 해당 없으면 빈 배열).
     */
    public record ItemResponse(
            Long productId,
            String gtin,
            String name,
            int qty,
            List<String> handling) {
    }
}
