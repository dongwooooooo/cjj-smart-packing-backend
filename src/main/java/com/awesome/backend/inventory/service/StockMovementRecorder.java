package com.awesome.backend.inventory.service;

/**
 * 재고 이동 기록의 단일 창구. 장부(inventory_tx) 기록과 캐시(stock_qty) 갱신을
 * 항상 한 트랜잭션으로 묶는다 — 호출자가 따로 챙길 수 없게 하기 위한 분리.
 */
public interface StockMovementRecorder {

    /** 수량 입고 (P1 stock-in). 재고 증가의 유일한 경로 (D-09). */
    void recordInbound(String gtin, int qty);

    /** 포장완료 차감 (P2). 부족해도 기록한다 — 잔고가 음수가 되면 정합성 대조기가 지표로 보고한다 (D-L1). */
    void recordOutboundPacked(String gtin, int qty, long shipmentId);

    /** 관리자 보정 (부호 포함). 같은 키 재전송은 기존 기록을 돌려준다. 같은 키에 다른 delta 는 IDEMPOTENCY_CONFLICT. */
    AdjustResult adjust(String gtin, int delta, String idempotencyKey, String reason);

    /**
     * 내부 보정 (부호 포함) — 재전송될 일이 없는 호출자용(데모 리셋, 테스트). 멱등 키가 없어
     * 재조회 대상도 없으므로 호출자의 트랜잭션에 그대로 참여한다. adjust() 와 달리 격리된
     * 트랜잭션으로 도피하지 않는다 — 같은 트랜잭션에서 방금 만든 상품 행도 곧바로 봐야 한다
     * (예: DemoProductProvisioner 가 상품을 만들고 바로 재고를 맞추는 순서).
     */
    AdjustResult adjustInternal(String gtin, int delta, String reason);

    record AdjustResult(long txId, int delta, boolean duplicated) {
    }
}
