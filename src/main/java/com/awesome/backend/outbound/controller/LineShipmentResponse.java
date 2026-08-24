package com.awesome.backend.outbound.controller;

/**
 * GET /api/v1/lines/{lineId}/shipments 응답 항목. docs/02-api-spec.md 3-1.
 *
 * @param toteBarcode 활성(released_at IS NULL) tote_assignment가 있으면 그 토트의 barcode, 없으면 null
 */
public record LineShipmentResponse(
        Long shipmentId,
        String receiptNo,
        int seqNo,
        String status,
        String toteBarcode) {
}
