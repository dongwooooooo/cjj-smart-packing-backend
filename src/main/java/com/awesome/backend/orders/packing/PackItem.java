package com.awesome.backend.orders.packing;

/**
 * 편성 대상 낱개 1개. block은 완충재 패딩이 이미 반영된 치수다.
 *
 * @param weightKg 상품 1개의 무게. 배송단위 총무게(요금 구간 판정의 한 축)를 이 값들의
 *                 합으로 구한다. 완충재 무게는 더하지 않는다 — 실측값이 없다.
 */
public record PackItem(String gtin, Block block, double weightKg,
                       boolean fragile, boolean nonStackable) {}
