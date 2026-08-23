package com.awesome.backend.orders.packing;

/**
 * 편성 대상 낱개 1개. block은 완충재 패딩이 이미 반영된 치수.
 */
public record PackItem(String gtin, Block block, boolean nonStackable) {}
