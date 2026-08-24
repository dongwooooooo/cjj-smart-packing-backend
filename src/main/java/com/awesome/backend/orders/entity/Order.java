package com.awesome.backend.orders.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {

    public enum Status { RECEIVED, ALLOCATED, IN_PACKING, PACKED, LOADED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_no", nullable = false, unique = true)
    private String receiptNo;

    @Column(name = "region_code", nullable = false)
    private String regionCode;

    @Column(name = "batch_id", nullable = false)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Order() {
    }

    public Order(String receiptNo, String regionCode, String batchId, LocalDateTime orderedAt) {
        this.receiptNo = receiptNo;
        this.regionCode = regionCode;
        this.batchId = batchId;
        this.status = Status.RECEIVED;
        this.orderedAt = orderedAt;
        this.createdAt = LocalDateTime.now();
    }

    public Long id() {
        return id;
    }

    public String receiptNo() {
        return receiptNo;
    }

    public String regionCode() {
        return regionCode;
    }

    public Status status() {
        return status;
    }

    public void allocate() {
        this.status = Status.ALLOCATED;
    }
}
