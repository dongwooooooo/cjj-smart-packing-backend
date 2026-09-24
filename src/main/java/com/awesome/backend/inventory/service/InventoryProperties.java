package com.awesome.backend.inventory.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 원장 기반 재고의 스케줄 주기 (specs/2026-09-23-ledger-stock-design.md D-L5).
 *
 * @param collector  스냅샷 집계기. 기본 5초 — 시연 화면 새로고침 주기와 같다
 * @param reconciler 정합성 대조기. 기본 60초 — 상품별 원장 전체 합 쿼리 비용을 고려한 값
 */
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(@DefaultValue Collector collector, @DefaultValue Reconciler reconciler) {

    public record Collector(@DefaultValue("5000") long intervalMs) {
    }

    public record Reconciler(@DefaultValue("60000") long intervalMs) {
    }
}
