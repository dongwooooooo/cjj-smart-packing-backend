package com.awesome.backend.inventory.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 재고 조정 요청. 재전송 대비 멱등 키 필수 (D-L7). */
public record InventoryAdjustmentRequest(
        @NotBlank String gtin,
        int delta,
        @NotBlank @Size(max = 80) String idempotencyKey,
        @Size(max = 200) String reason) {
}
