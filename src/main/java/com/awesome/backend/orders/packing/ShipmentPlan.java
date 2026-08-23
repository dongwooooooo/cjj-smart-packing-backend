package com.awesome.backend.orders.packing;

import java.util.List;

/**
 * 편성 결과 배송단위 1개 — 추천 박스와 담을 낱개들.
 */
public record ShipmentPlan(long boxId, List<PackItem> items) {}
