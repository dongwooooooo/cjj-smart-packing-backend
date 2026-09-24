package com.awesome.backend.inventory.controller;

import com.awesome.backend.inventory.service.AvailableStockQuery;
import com.awesome.backend.inventory.service.StockMovementRecorder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재고 조정 (specs/2026-09-23-ledger-stock-design.md D-L7). 조정은 이 경로 하나뿐이다. */
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class InventoryAdjustmentController {

    private final StockMovementRecorder recorder;
    private final AvailableStockQuery stockQuery;

    public InventoryAdjustmentController(StockMovementRecorder recorder, AvailableStockQuery stockQuery) {
        this.recorder = recorder;
        this.stockQuery = stockQuery;
    }

    @PostMapping("/adjustments")
    public InventoryAdjustmentResponse adjust(@Valid @RequestBody InventoryAdjustmentRequest request) {
        StockMovementRecorder.AdjustResult result = recorder.adjust(
                request.gtin(), request.delta(), request.idempotencyKey(), request.reason());
        return new InventoryAdjustmentResponse(result.txId(), request.gtin(), result.delta(),
                stockQuery.onHandQty(request.gtin()), result.duplicated());
    }
}
