package com.awesome.backend.inventory.application;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * MVP 위치 구현 — 가상 단일 위치 "MAIN"이 전 재고를 보유한다.
 * 실제 로케이션 관리가 생기면 이 구현체만 교체한다.
 */
public class SingleLocationStockService implements StockLocationService {

    static final String MAIN_LOCATION = "MAIN";

    private final ToIntFunction<String> onHandQty;

    public SingleLocationStockService(ToIntFunction<String> onHandQty) {
        this.onHandQty = onHandQty;
    }

    @Override
    public List<StockByLocation> stockByLocation(String gtin) {
        return List.of(new StockByLocation(MAIN_LOCATION, onHandQty.applyAsInt(gtin)));
    }

    @Override
    public List<PickInstruction> assignPickLocations(long shipmentId, Map<String, Integer> itemQuantities) {
        return itemQuantities.entrySet().stream()
                .map(e -> new PickInstruction(e.getKey(), e.getValue(), MAIN_LOCATION))
                .toList();
    }
}
