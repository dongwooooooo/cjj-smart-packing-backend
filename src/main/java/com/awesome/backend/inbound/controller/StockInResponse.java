package com.awesome.backend.inbound.controller;

/** 수량 입고 응답 (02 §1-5). {@code stockQty} 는 입고 반영 후의 현재 재고다. */
public record StockInResponse(Long productId, int stockQty) {
}
