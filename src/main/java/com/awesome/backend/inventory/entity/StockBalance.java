package com.awesome.backend.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 원장(inventory_tx)을 last_tx_id 까지 집계한 잔고 스냅샷. 집계기·대조기만 쓴다. */
@Entity
@Table(name = "stock_balance")
public class StockBalance {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "last_tx_id", nullable = false)
    private long lastTxId;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    protected StockBalance() {
    }

    public Long productId() {
        return productId;
    }

    public int qty() {
        return qty;
    }

    public long lastTxId() {
        return lastTxId;
    }

    public LocalDateTime computedAt() {
        return computedAt;
    }
}
