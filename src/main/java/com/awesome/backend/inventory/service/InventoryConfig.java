package com.awesome.backend.inventory.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InventoryConfig {

    /** MVP 위치 구현 — 가상 단일 위치. 실제 로케이션 관리 도입 시 이 빈만 교체. */
    @Bean
    public StockLocationService stockLocationService(InventoryService inventoryService) {
        return new SingleLocationStockService(inventoryService::onHandQty);
    }
}
