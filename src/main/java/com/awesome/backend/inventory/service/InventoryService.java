package com.awesome.backend.inventory.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.inventory.repository.StockBalanceRepository;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 이동·조회 구현. 장부 기록과 캐시 갱신을 한 트랜잭션으로 묶는 단일 창구.
 */
@Service
@Transactional
public class InventoryService implements AvailableStockQuery, StockMovementRecorder {

    private final ProductRepository productRepository;
    private final InventoryTxRepository inventoryTxRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final StockBalanceRepository stockBalanceRepository;

    public InventoryService(ProductRepository productRepository,
                            InventoryTxRepository inventoryTxRepository,
                            ShipmentItemRepository shipmentItemRepository,
                            StockBalanceRepository stockBalanceRepository) {
        this.productRepository = productRepository;
        this.inventoryTxRepository = inventoryTxRepository;
        this.shipmentItemRepository = shipmentItemRepository;
        this.stockBalanceRepository = stockBalanceRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public int onHandQty(String gtin) {
        return stockBalanceRepository.onHandQty(product(gtin).id());
    }

    @Override
    @Transactional(readOnly = true)
    public int availableQty(String gtin) {
        Long productId = product(gtin).id();
        return stockBalanceRepository.onHandQty(productId) - shipmentItemRepository.allocatedQty(productId);
    }

    @Override
    public void recordInbound(String gtin, int qty) {
        Product product = productForUpdate(gtin);
        product.changeStockQty(product.stockQty() + qty);
        inventoryTxRepository.save(
                new InventoryTx(product.id(), InventoryTx.TxType.INBOUND, qty, "STOCK_IN", null));
    }

    @Override
    public void recordOutboundPacked(String gtin, int qty, long shipmentId) {
        Product product = productForUpdate(gtin);
        if (product.stockQty() < qty) {
            throw new ApiException(ErrorCode.OUT_OF_STOCK, "재고가 부족합니다.",
                    Map.of("gtin", gtin, "requested", qty, "available", product.stockQty()));
        }
        product.changeStockQty(product.stockQty() - qty);
        inventoryTxRepository.save(
                new InventoryTx(product.id(), InventoryTx.TxType.OUTBOUND_PACKED, -qty, "SHIPMENT", shipmentId));
    }

    @Override
    public void adjust(String gtin, int delta) {
        Product product = productForUpdate(gtin);
        product.changeStockQty(product.stockQty() + delta);
        inventoryTxRepository.save(
                new InventoryTx(product.id(), InventoryTx.TxType.ADJUST, delta, null, null));
    }

    private Product product(String gtin) {
        return productRepository.findByGtin(gtin)
                .orElseThrow(() -> notFound(gtin));
    }

    /** 쓰기 경로 전용 — 행 잠금으로 동시 증감의 lost update를 막는다. */
    private Product productForUpdate(String gtin) {
        return productRepository.findByGtinForUpdate(gtin)
                .orElseThrow(() -> notFound(gtin));
    }

    private ApiException notFound(String gtin) {
        return new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                "상품을 찾을 수 없습니다.", Map.of("gtin", gtin));
    }
}
