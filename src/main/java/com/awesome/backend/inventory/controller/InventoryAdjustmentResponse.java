package com.awesome.backend.inventory.controller;

public record InventoryAdjustmentResponse(long txId, String gtin, int delta, int onHandQty, boolean duplicated) {
}
