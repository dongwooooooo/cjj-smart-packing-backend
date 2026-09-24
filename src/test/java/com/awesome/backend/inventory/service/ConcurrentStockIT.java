package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 동시 재고 증감의 lost update 회귀 테스트.
 * 클래스 레벨 @Transactional 없음 — 스레드마다 실제 커밋이 일어나야 검증이 된다.
 */
@SpringBootTest
@Testcontainers
class ConcurrentStockIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RAMEN = "8801234500066";
    private static final int THREADS = 8;

    @Autowired InventoryService inventoryService;

    @Test
    void 동시_입고_기록이_유실되지_않는다() throws InterruptedException {
        int baseline = inventoryService.onHandQty(RAMEN);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    inventoryService.recordInbound(RAMEN, 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(inventoryService.onHandQty(RAMEN)).isEqualTo(baseline + THREADS);

        inventoryService.adjust(RAMEN, -THREADS, "internal-" + java.util.UUID.randomUUID(), "test"); // 컨테이너 재사용 대비 원복
    }
}
