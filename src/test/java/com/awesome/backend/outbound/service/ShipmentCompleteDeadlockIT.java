package com.awesome.backend.outbound.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inventory.service.StockMovementRecorder;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 부하 테스트 실행 4(2026-09-22)의 데드락 회귀 테스트. 품목 순서가 [JUICE, GRAPE]인 배송단위와
 * [GRAPE, JUICE]인 배송단위를 두 스레드가 동시에 완료한다. 상품 행을 shipment_item 순서로 잠그던
 * 이전 코드에서는 한쪽이 deadlock detected 로 끝났다. 원장 기반 재고에서는 포장 완료가 원장 행만
 * 추가하고 박스 행 하나만 잠가 둘 다 성공해야 한다. 클래스 레벨 @Transactional 없음 — 스레드마다
 * 실제 트랜잭션이 필요하다.
 */
@SpringBootTest
@Testcontainers
class ShipmentCompleteDeadlockIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String JUICE = "8801234500011";
    private static final String GRAPE = "8801234500028";
    private static final long LINE_ID = 1L;
    private static final long BOX_A = 1L;
    private static final int ROUNDS = 20;

    @Autowired ShipmentCompleteService completeService;
    @Autowired StockMovementRecorder stockMovementRecorder;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentItemRepository shipmentItemRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired ToteAssignmentRepository toteAssignmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 반대_순서_품목을_동시에_완료해도_둘_다_성공한다() throws Exception {
        stockMovementRecorder.adjustInternal(JUICE, 1_000, "test");
        stockMovementRecorder.adjustInternal(GRAPE, 1_000, "test");
        long juice = productRepository.findByGtin(JUICE).orElseThrow().id();
        long grape = productRepository.findByGtin(GRAPE).orElseThrow().id();

        long packedRowsBefore = packedRows(juice, grape);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        int failures = 0;
        List<String> errors = new ArrayList<>();
        List<Long> lastRound = List.of();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long a = packingShipment(List.of(juice, grape));
                long b = packingShipment(List.of(grape, juice));
                lastRound = List.of(a, b);
                CountDownLatch start = new CountDownLatch(1);
                Future<Throwable> fa = pool.submit(() -> run(start, a));
                Future<Throwable> fb = pool.submit(() -> run(start, b));
                start.countDown();
                for (Future<Throwable> f : List.of(fa, fb)) {
                    Throwable t = f.get(60, TimeUnit.SECONDS);
                    if (t != null) {
                        failures++;
                        errors.add(t.getClass().getSimpleName() + ": " + firstLine(t.getMessage()));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures).as("실패 목록: %s", errors).isZero();
        for (long shipmentId : lastRound) {
            assertThat(shipmentRepository.findById(shipmentId).orElseThrow().status())
                    .isEqualTo(Shipment.Status.PACKED);
        }
        // 라운드마다 배송단위 2개 × 품목 2개 = 포장 원장 4행
        assertThat(packedRows(juice, grape) - packedRowsBefore).isEqualTo(2L * ROUNDS * 2);
    }

    private long packedRows(long juice, long grape) {
        return jdbcTemplate.queryForObject("""
                select count(*) from inventory_tx
                 where tx_type = 'OUTBOUND_PACKED' and product_id in (?, ?)
                """, Long.class, juice, grape);
    }

    private Throwable run(CountDownLatch start, long shipmentId) {
        try {
            start.await();
            completeService.complete(shipmentId, null);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** PACKING 상태 배송단위 + 품목(주어진 순서로 저장) + 유휴 토트 배정. */
    private long packingShipment(List<Long> productIds) {
        Order order = orderRepository.save(
                new Order("R-DL-" + System.nanoTime(), "SEOUL", "B-DL", LocalDateTime.now()));
        Shipment shipment = new Shipment(order.id(), 1, LINE_ID, BOX_A, false);
        ReflectionTestUtils.setField(shipment, "status", Shipment.Status.PACKING);
        shipment = shipmentRepository.save(shipment);
        for (Long productId : productIds) {
            shipmentItemRepository.save(new ShipmentItem(shipment.id(), productId, 1));
        }
        Tote tote = toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE).stream()
                .findFirst().orElseThrow();
        tote.assign();
        toteRepository.save(tote);
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), shipment.id()));
        return shipment.id();
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
