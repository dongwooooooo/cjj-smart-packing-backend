package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.entity.StockBalance;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.inventory.repository.StockBalanceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 클래스 레벨 @Transactional 없음 — 집계기는 커밋된 원장만 봐야 한다. */
@SpringBootTest(properties = {
        "inventory.collector.interval-ms=3600000",
        // 집계 산술·동시성만 본다. 정착 창은 StockBalanceCollectorSettleIT 가 따로 검증한다.
        "inventory.collector.settle-seconds=0"})
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
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, 1, null, null));

        int updated = collector.collectOnce();
        StockBalance after = stockBalanceRepository.findByProductId(productId).orElseThrow();

        assertThat(updated).isGreaterThanOrEqualTo(1);
        assertThat(after.qty()).isEqualTo(before.qty() + 7);
        assertThat(after.lastTxId()).isEqualTo(idOf(productId));
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

    @Test
    void 동시_집계와_동시_원장_추가에서도_스냅샷은_원장_합과_같다() throws Exception {
        Long productId = productRepository.findByGtin(PIE).orElseThrow().id();
        collector.collectOnce();

        Runnable collectLoop = () -> {
            for (int i = 0; i < 15; i++) {
                collector.collectOnce();
            }
        };
        Runnable insertLoop = () -> {
            for (int i = 0; i < 30; i++) {
                inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, 1, null, null));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(collectLoop), executor.submit(collectLoop), executor.submit(insertLoop));
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
        }

        collector.collectOnce();

        Integer ledgerTotal = jdbcTemplate.queryForObject(
                "select coalesce(sum(qty_delta),0) from inventory_tx where product_id = ?", Integer.class, productId);
        Long maxId = jdbcTemplate.queryForObject(
                "select max(id) from inventory_tx where product_id = ?", Long.class, productId);
        StockBalance after = stockBalanceRepository.findByProductId(productId).orElseThrow();

        assertThat(after.qty()).isEqualTo(ledgerTotal);
        assertThat(after.lastTxId()).isEqualTo(maxId);
    }

    private long idOf(Long productId) {
        return jdbcTemplate.queryForObject(
                "select max(id) from inventory_tx where product_id = ?", Long.class, productId);
    }
}
