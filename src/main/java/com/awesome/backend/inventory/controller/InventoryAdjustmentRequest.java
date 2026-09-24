package com.awesome.backend.inventory.controller;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 재고 조정 요청. 재전송 대비 멱등 키 필수 (D-L7). delta 는 필수이고 0 이 아니어야 한다 — 빠진 필드가
 * 0 으로 기록되면 의미 없는 원장 행이 남고 멱등 키도 소모된다.
 */
public record InventoryAdjustmentRequest(
        @NotBlank String gtin,
        @NotNull Integer delta,
        @NotBlank @Size(max = 80) String idempotencyKey,
        @Size(max = 200) String reason) {

    @AssertTrue(message = "delta 는 0 이 아니어야 합니다.")
    public boolean isDeltaNonZero() {
        return delta == null || delta != 0;
    }
}
