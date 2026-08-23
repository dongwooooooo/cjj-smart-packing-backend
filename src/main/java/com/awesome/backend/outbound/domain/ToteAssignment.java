package com.awesome.backend.outbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 토트 할당 이력. 활성 행(released_at IS NULL)은 토트당·배송단위당 최대 1개 —
 * DB partial unique index가 강제한다 (V1).
 */
@Entity
@Table(name = "tote_assignment")
public class ToteAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tote_id", nullable = false)
    private Long toteId;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    protected ToteAssignment() {
    }

    public ToteAssignment(Long toteId, Long shipmentId) {
        this.toteId = toteId;
        this.shipmentId = shipmentId;
        this.assignedAt = LocalDateTime.now();
    }

    public Long toteId() {
        return toteId;
    }

    public Long shipmentId() {
        return shipmentId;
    }

    public void release() {
        this.releasedAt = LocalDateTime.now();
    }
}
