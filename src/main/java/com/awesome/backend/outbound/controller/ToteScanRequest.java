package com.awesome.backend.outbound.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/totes/scan 요청 바디. docs/02-api-spec.md 3-5.
 *
 * <p>토트 바코드는 GTIN과 달리 자유 형식 문자열(V2 seed 예: {@code TOTE-001})이라
 * 상품 스캔 요청({@link com.awesome.backend.inbound.scan.dto.ScanRequest})과 달리
 * {@code @Pattern} 제약을 두지 않는다.
 */
public record ToteScanRequest(@NotBlank(message = "바코드는 필수입니다.") String barcode) {
}
