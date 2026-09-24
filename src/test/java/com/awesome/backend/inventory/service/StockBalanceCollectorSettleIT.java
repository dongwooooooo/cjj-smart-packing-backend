package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.entity.StockBalance;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.inventory.repository.StockBalanceRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 정착 창 검증. StockBalanceCollectorIT 는 창을 0 으로 두고 집계 산술을 보므로, 창이 있어야 성립하는
 * 이 시나리오는 별도 컨텍스트(창 2초)에서 돌린다. 스케줄 집계는 꺼 둔다.
 */
@SpringBootTest(properties = {
        "inventory.collector.interval-ms=3600000",
        "inventory.reconciler.interval-ms=3600000",
        "inventory.collector.settle-seconds=5"})
@Testcontainers
class StockBalanceCollectorSettleIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PIE = "8801234500059";
    private static final long SETTLE_WAIT_MS = 5_500;

    @Autowired StockBalanceCollector collector;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired ProductRepository productRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * 포장 완료처럼 원장 행을 넣은 뒤 다른 락을 기다리는 트랜잭션 A 가 id N 을 받고, 그 사이 id N+1 이
     * 먼저 커밋된다. 옛 커서(보이는 행의 MAX(id))라면 집계가 last_tx_id 를 N+1 로 옮겨 A 의 −1 이
     * 스냅샷에도 조회에도 영원히 빠진다.
     */
    @Test
    void 커밋_순서가_뒤바뀐_원장_행도_빠지지_않는다() throws Exception {
        Long productId = productRepository.findByGtin(PIE).orElseThrow().id();
        collector.collectOnce();

        AtomicLong slowIdHolder = new AtomicLong();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> slowTx = executor.submit(() -> transactionTemplate.execute(status -> {
                long id = inventoryTxRepository.save(
                        new InventoryTx(productId, InventoryTx.TxType.ADJUST, -1, null, null)).id();
                slowIdHolder.set(id);
                inserted.countDown();
                await(release);
                return id;
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            long fastId = inventoryTxRepository.save(
                    new InventoryTx(productId, InventoryTx.TxType.ADJUST, -2, null, null)).id();

            // A 는 아직 커밋 전이다. N+1 만 보이지만 창 안에 있어 접지 않는다.
            collector.collectOnce();
            StockBalance duringOpenTx = stockBalanceRepository.findByProductId(productId).orElseThrow();
            assertThat(slowIdHolder.get()).isLessThan(fastId);
            assertThat(duringOpenTx.lastTxId()).isLessThan(slowIdHolder.get());
            // 조회(스냅샷 + 미집계)는 커밋된 원장 합과 같다.
            assertThat(stockBalanceRepository.onHandQty(productId)).isEqualTo(ledgerTotal(productId));

            release.countDown();
            slowTx.get(10, TimeUnit.SECONDS);
            // A 가 커밋하자마자 조회에 −1 이 반영된다 — last_tx_id 가 N 을 넘지 않았기 때문이다.
            assertThat(stockBalanceRepository.onHandQty(productId)).isEqualTo(ledgerTotal(productId));

            Thread.sleep(SETTLE_WAIT_MS);
            collector.collectOnce();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        StockBalance settled = stockBalanceRepository.findByProductId(productId).orElseThrow();
        assertThat(settled.qty()).isEqualTo(ledgerTotal(productId));
        assertThat(settled.lastTxId()).isEqualTo(maxId(productId));
    }

    private int ledgerTotal(Long productId) {
        return jdbcTemplate.queryForObject(
                "select coalesce(sum(qty_delta),0) from inventory_tx where product_id = ?", Integer.class, productId);
    }

    private long maxId(Long productId) {
        return jdbcTemplate.queryForObject(
                "select max(id) from inventory_tx where product_id = ?", Long.class, productId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
