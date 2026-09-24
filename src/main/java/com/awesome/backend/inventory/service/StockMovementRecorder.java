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

    /** 관리자 보정 (부호 포함). */
    void adjust(String gtin, int delta);
}
