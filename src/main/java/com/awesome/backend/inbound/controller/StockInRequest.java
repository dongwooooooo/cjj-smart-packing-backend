package com.awesome.backend.inbound.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 수량 입고 요청 (02 §1-5).
 *
 * <p>측정한 실물도 원래 입고분에 돌려놓으므로 {@code qty} 는 촬영분을 포함한 전체 수량이다.
 */
public record StockInRequest(
        @NotNull(message = "productId 는 필수입니다.")
        Long productId,

        // 감소는 관리자 보정(admin/inventory/adjust) 몫이다. 입고로 재고를 줄일 수는 없다.
        @NotNull(message = "qty 는 필수입니다.")
        @Positive(message = "입고 수량은 1 이상이어야 합니다.")
        Integer qty) {
}
