package com.awesome.backend.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Transactional
class StockBalanceRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String CIDER = "8801234500035";

    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 마이그레이션이_상품마다_스냅샷_행을_만든다() {
        Long products = jdbcTemplate.queryForObject("select count(*) from product", Long.class);
        Long balances = jdbcTemplate.queryForObject("select count(*) from stock_balance", Long.class);
        assertThat(balances).isEqualTo(products);
    }

    @Test
    void 실재고는_스냅샷에_미집계_원장을_더한_값이다() {
        Long productId = productRepository.findByGtin(CIDER).orElseThrow().id();
        jdbcTemplate.update("update stock_balance set qty = 100, last_tx_id = "
                + "(select coalesce(max(id),0) from inventory_tx where product_id = ?) where product_id = ?",
                productId, productId);
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.INBOUND, 5, "STOCK_IN", null));
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.OUTBOUND_PACKED, -3, "SHIPMENT", 1L));
        inventoryTxRepository.flush();

        assertThat(stockBalanceRepository.onHandQty(productId)).isEqualTo(102);
    }

    @Test
    void 스냅샷_행이_없는_상품은_원장_전체_합이다() {
        Long productId = productRepository.findByGtin(CIDER).orElseThrow().id();
        jdbcTemplate.update("delete from stock_balance where product_id = ?", productId);
        jdbcTemplate.update("delete from inventory_tx where product_id = ?", productId);
        inventoryTxRepository.save(new InventoryTx(productId, InventoryTx.TxType.ADJUST, 7, null, null));
        inventoryTxRepository.flush();

        assertThat(stockBalanceRepository.onHandQty(productId)).isEqualTo(7);
    }

    @Test
    void 뷰는_같은_계산을_돌려준다() {
        Long productId = productRepository.findByGtin(CIDER).orElseThrow().id();
        Integer fromView = jdbcTemplate.queryForObject(
                "select on_hand_qty from v_stock_on_hand where product_id = ?", Integer.class, productId);
        assertThat(fromView).isEqualTo(stockBalanceRepository.onHandQty(productId));
    }
}
