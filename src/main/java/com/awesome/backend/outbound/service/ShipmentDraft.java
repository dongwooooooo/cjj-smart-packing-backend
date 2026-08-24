package com.awesome.backend.outbound.service;

import java.util.List;

/**
 * 저장 직전의 배송단위 1개. 편성 결과(orders/packing)를 outbound가 그대로 받지 않고
 * 이 형태로 옮겨 받는다 — outbound는 편성 알고리즘의 자료구조를 알 필요가 없다.
 */
public record ShipmentDraft(int seqNo, long recommendedBoxId, boolean fillerRecommended,
                            List<ItemDraft> items) {

    public record ItemDraft(long productId, int qty) {
    }
}
