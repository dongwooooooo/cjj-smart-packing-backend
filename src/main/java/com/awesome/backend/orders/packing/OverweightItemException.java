package com.awesome.backend.orders.packing;

/**
 * 낱개 하나가 택배사 접수 무게 한도를 넘는다 — 주문 거부 사유 OVERWEIGHT_ITEM.
 *
 * <p>나눠 담아도 해결되지 않는다는 점에서 {@link OversizedItemException}과 같은 성격이다.
 * 치수는 들어가는데 무게만 걸리는 경우를 가려내려고 사유를 나눴다.
 */
public class OverweightItemException extends RuntimeException {

    private final String gtin;
    private final double weightKg;

    public OverweightItemException(String gtin, double weightKg) {
        super("item exceeds carrier weight limit: " + gtin + " (" + weightKg + "kg)");
        this.gtin = gtin;
        this.weightKg = weightKg;
    }

    public String gtin() {
        return gtin;
    }

    public double weightKg() {
        return weightKg;
    }
}
