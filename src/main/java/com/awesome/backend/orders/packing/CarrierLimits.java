package com.awesome.backend.orders.packing;

/**
 * 택배사 접수 한도 — 넘으면 그 배송단위는 만들 수 없다 (분할하거나, 낱개 하나가 넘으면 거부).
 *
 * <p>기본값은 요금 구간표(shipping_rate_tier)의 최상단 구간과 맞춘다. 세 변의 합과 최장변은
 * 박스 외치수로 잰다.
 */
public record CarrierLimits(double maxSumCm, double maxLongestCm, double maxWeightKg) {

    /** 이 박스에 총무게 weightKg을 담은 배송단위가 접수 한도 안에 있는가. */
    public boolean allows(CatalogBox box, double weightKg) {
        return box.outerSumCm() <= maxSumCm
                && box.outerLongestCm() <= maxLongestCm
                && weightKg <= maxWeightKg;
    }

    public boolean allowsWeight(double weightKg) {
        return weightKg <= maxWeightKg;
    }
}
