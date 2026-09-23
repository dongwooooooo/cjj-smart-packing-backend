package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
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
    void 수량_입고는_장부_기록과_캐시_증가를_함께_한다() {
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
    void 포장완료_차감은_장부와_캐시를_함께_줄인다() {
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
    void 재고보다_많은_차감은_거부한다() {
        inventoryService.recordInbound(JUICE, 3);

        assertThatThrownBy(() -> inventoryService.recordOutboundPacked(JUICE, 5, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.OUT_OF_STOCK));
    }

    private Long plannedShipment(Long productId, int qty) {
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), "SEOUL", "B-IT", LocalDateTime.now()));
        Shipment shipment = shipmentRepository.save(new Shipment(order.id(), 1, 1L, 1L, false));
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), productId, qty));
        return shipment.id();
    }
}
