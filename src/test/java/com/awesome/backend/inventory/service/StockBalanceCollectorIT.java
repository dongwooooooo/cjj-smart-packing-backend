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

/** 클래스 레벨 @Transactional 없음 — 집계기는 커밋된 원장만 봐야 한다. */
@SpringBootTest(properties = "inventory.collector.interval-ms=3600000")
@Testcontainers
class StockBalanceCollectorIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PIE = "8801234500059";

    @Autowired StockBalanceCollector collector;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired ProductRepository productRepository;
    @Autowired MeterRegistry registry;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 미집계_원장을_스냅샷에_더하고_last_tx_id를_전진시킨다() {
        Long productId = productRepository.findByGtin(PIE).orElseThrow().id();
        collector.collectOnce();
        StockBalance before = stockBalanceRepository.findByProductId(productId).orElseThrow();

        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.INBOUND, 10, "STOCK_IN", null));
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.OUTBOUND_PACKED, -4, "SHIPMENT", 1L));
        InventoryTx last = inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, 1, null, null));

        int updated = collector.collectOnce();
        StockBalance after = stockBalanceRepository.findByProductId(productId).orElseThrow();

        assertThat(updated).isGreaterThanOrEqualTo(1);
        assertThat(after.qty()).isEqualTo(before.qty() + 7);
        assertThat(after.lastTxId()).isEqualTo(idOf(last));
        assertThat(registry.get("inventory.collector.lag_rows").gauge().value()).isZero();
    }

    @Test
    void 스냅샷_행이_없는_상품은_집계_때_행이_생긴다() {
        Long productId = productRepository.findByGtin(PIE).orElseThrow().id();
        jdbcTemplate.update("delete from stock_balance where product_id = ?", productId);
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, 3, null, null));

        collector.collectOnce();

        StockBalance created = stockBalanceRepository.findByProductId(productId).orElseThrow();
        Integer ledgerTotal = jdbcTemplate.queryForObject(
                "select coalesce(sum(qty_delta),0) from inventory_tx where product_id = ?", Integer.class, productId);
        assertThat(created.qty()).isEqualTo(ledgerTotal);
    }

    @Test
    void 두_번_연속_집계해도_이중_반영되지_않는다() {
        Long productId = productRepository.findByGtin(PIE).orElseThrow().id();
        collector.collectOnce();
        int base = stockBalanceRepository.findByProductId(productId).orElseThrow().qty();
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, 5, null, null));

        collector.collectOnce();
        collector.collectOnce();

        assertThat(stockBalanceRepository.findByProductId(productId).orElseThrow().qty()).isEqualTo(base + 5);
    }

    private long idOf(InventoryTx tx) {
        return jdbcTemplate.queryForObject("select max(id) from inventory_tx", Long.class);
    }
}
