package com.awesome.backend.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 재고 원장 — 일어난 이동만 기록한다. 약속(할당)은 배송단위에서 유도 (concepts/inventory-allocation).
 */
@Entity
@Table(name = "inventory_tx")
public class InventoryTx {

    public enum TxType { INBOUND, OUTBOUND_PACKED, ADJUST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false)
    private TxType txType;

    @Column(name = "qty_delta", nullable = false)
    private int qtyDelta;

    @Column(name = "ref_type")
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected InventoryTx() {
    }

    public InventoryTx(Long productId, TxType txType, int qtyDelta, String refType, Long refId) {
        this.productId = productId;
        this.txType = txType;
        this.qtyDelta = qtyDelta;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = LocalDateTime.now();
    }

    public TxType txType() {
        return txType;
    }

    public int qtyDelta() {
        return qtyDelta;
    }
}
