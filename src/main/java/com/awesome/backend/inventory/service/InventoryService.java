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
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 이동·조회 구현. 쓰기는 원장(inventory_tx) 추가만, 읽기는 스냅샷+미집계 차분
 * (specs/2026-09-23-ledger-stock-design.md).
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
        inventoryTxRepository.save(
                new InventoryTx(product(gtin).id(), InventoryTx.TxType.INBOUND, qty, "STOCK_IN", null));
    }

    /** 포장 완료는 실물이 나갔다는 사실의 기록이다. 부족해도 막지 않는다 — 음수 잔고는 대조기가 보고한다 (D-L1). */
    @Override
    public void recordOutboundPacked(String gtin, int qty, long shipmentId) {
        inventoryTxRepository.save(
                new InventoryTx(product(gtin).id(), InventoryTx.TxType.OUTBOUND_PACKED, -qty, "SHIPMENT", shipmentId));
    }

    @Override
    public AdjustResult adjust(String gtin, int delta, String idempotencyKey, String reason) {
        Long productId = product(gtin).id();
        Optional<InventoryTx> existing = inventoryTxRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            InventoryTx tx = existing.get();
            if (tx.qtyDelta() != delta || !tx.productId().equals(productId)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "같은 멱등 키로 다른 조정이 이미 기록돼 있습니다.",
                        Map.of("idempotencyKey", idempotencyKey, "recordedDelta", tx.qtyDelta()));
            }
            return new AdjustResult(tx.id(), tx.qtyDelta(), true);
        }
        InventoryTx saved = inventoryTxRepository.save(new InventoryTx(productId, delta, idempotencyKey, reason));
        return new AdjustResult(saved.id(), delta, false);
    }

    private Product product(String gtin) {
        return productRepository.findByGtin(gtin)
                .orElseThrow(() -> notFound(gtin));
    }

    private ApiException notFound(String gtin) {
        return new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                "상품을 찾을 수 없습니다.", Map.of("gtin", gtin));
    }
}
