package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.entity.StockBalance;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.inventory.repository.StockBalanceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "inventory.collector.interval-ms=3600000",
        "inventory.reconciler.interval-ms=3600000",
        "inventory.collector.settle-seconds=0"})
@Testcontainers
class StockReconcilerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String GRAPE = "8801234500028";

    @Autowired StockReconciler reconciler;
    @Autowired StockBalanceCollector collector;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired ProductRepository productRepository;
    @Autowired MeterRegistry registry;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 훼손된_스냅샷을_원장_기준으로_복구하고_불일치를_센다() {
        Long productId = productRepository.findByGtin(GRAPE).orElseThrow().id();
        collector.collectOnce();
        Integer ledgerTotal = jdbcTemplate.queryForObject(
                "select coalesce(sum(qty_delta),0) from inventory_tx where product_id = ?", Integer.class, productId);
        jdbcTemplate.update("update stock_balance set qty = qty + 999 where product_id = ?", productId);

        int fixed = reconciler.reconcileOnce();

        assertThat(fixed).isEqualTo(1);
        assertThat(stockBalanceRepository.onHandQty(productId)).isEqualTo(ledgerTotal);
        Long maxId = jdbcTemplate.queryForObject(
                "select coalesce(max(id),0) from inventory_tx where product_id = ?", Long.class, productId);
        StockBalance rebuilt = stockBalanceRepository.findByProductId(productId).orElseThrow();
        assertThat(rebuilt.qty()).isEqualTo(ledgerTotal);
        assertThat(rebuilt.lastTxId()).isEqualTo(maxId);
        assertThat(registry.get("inventory.reconcile.mismatch").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void 일치하면_아무것도_바꾸지_않고_불일치_0이다() {
        collector.collectOnce();
        int fixed = reconciler.reconcileOnce();
        assertThat(fixed).isZero();
        assertThat(registry.get("inventory.reconcile.mismatch").gauge().value()).isZero();
    }

    @Test
    void 음수_잔고_상품_수를_센다() {
        Long productId = productRepository.findByGtin(GRAPE).orElseThrow().id();
        int onHand = stockBalanceRepository.onHandQty(productId);
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.OUTBOUND_PACKED, -(onHand + 1), "SHIPMENT", 1L));

        reconciler.reconcileOnce();

        assertThat(registry.get("inventory.balance.negative").gauge().value()).isGreaterThanOrEqualTo(1.0);
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, onHand + 1, null, null)); // 원복
    }
}
