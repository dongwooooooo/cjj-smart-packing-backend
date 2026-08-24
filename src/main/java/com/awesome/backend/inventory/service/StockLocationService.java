package com.awesome.backend.inventory.service;

import java.util.List;
import java.util.Map;

/**
 * Hard allocation 경계 — 재고의 실물 위치.
 * 위치 관리는 현재 블랙박스지만, 인터페이스는 위치가 여럿인 전제로 정의한다.
 * MVP 구현은 가상 단일 위치가 전 재고를 보유 (SingleLocationStockService).
 */
public interface StockLocationService {

    /** SKU 재고가 어느 위치에 몇 개 있는지. */
    List<StockByLocation> stockByLocation(String gtin);

    /** 배송단위의 품목들을 어느 위치에서 꺼낼지 — 피킹 지시. */
    List<PickInstruction> assignPickLocations(long shipmentId, Map<String, Integer> itemQuantities);

    record StockByLocation(String locationCode, int qty) {}

    record PickInstruction(String gtin, int qty, String locationCode) {}
}
