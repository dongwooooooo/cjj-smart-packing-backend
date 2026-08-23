package com.awesome.backend.orders.packing;

/**
 * 카탈로그의 어떤 박스에도 들어가지 않는 낱개 — 주문 거부 사유 OVERSIZED_ITEM.
 */
public class OversizedItemException extends RuntimeException {

    private final String gtin;

    public OversizedItemException(String gtin) {
        super("no catalog box can hold item: " + gtin);
        this.gtin = gtin;
    }

    public String gtin() {
        return gtin;
    }
}
