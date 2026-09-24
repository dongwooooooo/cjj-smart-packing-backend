package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Transactional
class InventoryServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    // V2 seed의 시연 상품
    private static final String JUICE = "8801234500011";

    @Autowired InventoryService inventoryService;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentItemRepository shipmentItemRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void 실재고와_가용재고는_스냅샷에_미집계_원장을_더해_계산한다() {
        Long productId = productRepository.findByGtin(JUICE).orElseThrow().id();
        // 스냅샷을 100 으로 고정하고, 그 뒤 원장에 +5, -3 을 넣는다.
        jdbcTemplate.update("update stock_balance set qty = 100, last_tx_id = "
                + "(select coalesce(max(id),0) from inventory_tx where product_id = ?) where product_id = ?",
                productId, productId);
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.INBOUND, 5, "STOCK_IN", null));
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.OUTBOUND_PACKED, -3, "SHIPMENT", 1L));
        inventoryTxRepository.flush();
        plannedShipment(productId, 10);

        assertThat(inventoryService.onHandQty(JUICE)).isEqualTo(102);
        assertThat(inventoryService.availableQty(JUICE)).isEqualTo(92);
    }

    @Test
    void 수량_입고는_원장에_기록되고_실재고에_반영된다() {
        inventoryService.recordInbound(JUICE, 10);

        assertThat(inventoryService.onHandQty(JUICE)).isEqualTo(10);
        Long productId = productRepository.findByGtin(JUICE).orElseThrow().id();
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(productId))
                .anySatisfy(tx -> {
                    assertThat(tx.txType()).isEqualTo(InventoryTx.TxType.INBOUND);
                    assertThat(tx.qtyDelta()).isEqualTo(10);
                });
    }

    @Test
    void 가용재고는_포장_미완료_배송단위의_약속_수량을_뺀다() {
        inventoryService.recordInbound(JUICE, 10);
        Long productId = productRepository.findByGtin(JUICE).orElseThrow().id();
        Long shipmentId = plannedShipment(productId, 4);

        assertThat(inventoryService.onHandQty(JUICE)).isEqualTo(10);
        assertThat(inventoryService.availableQty(JUICE)).isEqualTo(6);
        assertThat(shipmentId).isNotNull();
    }

    @Test
    void 포장완료_차감은_원장에_기록되고_실재고를_줄인다() {
        inventoryService.recordInbound(JUICE, 10);
        Long productId = productRepository.findByGtin(JUICE).orElseThrow().id();
        Long shipmentId = plannedShipment(productId, 4);

        inventoryService.recordOutboundPacked(JUICE, 4, shipmentId);

        assertThat(inventoryService.onHandQty(JUICE)).isEqualTo(6);
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(productId))
                .anySatisfy(tx -> {
                    assertThat(tx.txType()).isEqualTo(InventoryTx.TxType.OUTBOUND_PACKED);
                    assertThat(tx.qtyDelta()).isEqualTo(-4);
                });
    }

    @Test
    void 재고보다_많은_차감도_기록하고_실재고는_음수가_된다() {
        inventoryService.recordInbound(JUICE, 3);
        inventoryService.recordOutboundPacked(JUICE, 5, 1L);
        assertThat(inventoryService.onHandQty(JUICE)).isEqualTo(-2);
    }

    @Test
    void 쓰기_경로는_원장만_추가하고_상품_행을_갱신하지_않는다() {
        // adjustInternal() 사용 — adjust() 는 멱등 재조회를 위해 Propagation.NOT_SUPPORTED 로
        // 실행되므로, 이 테스트의 클래스 레벨 @Transactional 롤백을 벗어나 실제로 커밋돼
        // 버린다. adjustInternal() 은 재시도 대상이 아닌 내부 호출자용이라 호출자의 트랜잭션에
        // 그대로 참여하고, 검증하려는 "쓰기는 원장만 추가한다"는 성질 자체는 두 경로가 같다.
        Long productId = productRepository.findByGtin(JUICE).orElseThrow().id();
        Integer stockQtyBefore = jdbcTemplate.queryForObject(
                "select stock_qty from product where id = ?", Integer.class, productId);
        int txCountBefore = inventoryTxRepository.findByProductIdOrderByIdAsc(productId).size();

        inventoryService.recordInbound(JUICE, 10);
        inventoryService.recordOutboundPacked(JUICE, 4, 1L);
        inventoryService.adjustInternal(JUICE, -1, "test");
        inventoryTxRepository.flush();

        Integer stockQtyAfter = jdbcTemplate.queryForObject(
                "select stock_qty from product where id = ?", Integer.class, productId);
        assertThat(stockQtyAfter).isEqualTo(stockQtyBefore);
        assertThat(inventoryService.onHandQty(JUICE)).isEqualTo(5);
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(productId))
                .hasSize(txCountBefore + 3);
    }

    private Long plannedShipment(Long productId, int qty) {
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), "SEOUL", "B-IT", LocalDateTime.now()));
        Shipment shipment = shipmentRepository.save(new Shipment(order.id(), 1, 1L, 1L, false));
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), productId, qty));
        return shipment.id();
    }
}
