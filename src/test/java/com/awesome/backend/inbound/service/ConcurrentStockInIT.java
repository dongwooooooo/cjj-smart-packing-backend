package com.awesome.backend.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.service.InventoryService;
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
 * 1-5 경로에서도 행 잠금이 유지되는지 본다.
 *
 * <p>inventory 의 ConcurrentStockIT 는 창구를 직접 부르지만, 1-5 는 productId → gtin 변환이
 * 한 단계 끼어든다. 그 변환에서 Product 엔티티를 먼저 로드해버리면 창구의 SELECT FOR UPDATE 가
 * 영속성 컨텍스트의 잠금 전 인스턴스를 돌려주고, 잠금이 있어도 lost update 가 난다.
 * 이 테스트가 그 회귀를 잡는다.
 *
 * <p>클래스 레벨 @Transactional 없음 — 스레드마다 실제 커밋이 일어나야 검증이 된다.
 */
@SpringBootTest
@Testcontainers
class ConcurrentStockInIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PIE = "8801234500059";
    private static final int THREADS = 8;

    @Autowired StockInService stockInService;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryService inventoryService;

    @Test
    void 동시_수량입고가_유실되지_않는다() throws InterruptedException {
        Long productId = productRepository.findByGtin(PIE).map(Product::id).orElseThrow();
        int baseline = inventoryService.onHandQty(PIE);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    stockInService.stockIn(productId, 1);
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

        assertThat(inventoryService.onHandQty(PIE)).isEqualTo(baseline + THREADS);

        inventoryService.adjustInternal(PIE, -THREADS, "test"); // 컨테이너 재사용 대비 원복
    }
}
