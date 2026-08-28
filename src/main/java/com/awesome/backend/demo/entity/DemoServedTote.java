package com.awesome.backend.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 포장 시연에서 화면에 이미 내준 토트. 같은 토트를 두 번 내주지 않으려는 표시다.
 *
 * <p>배송단위 자체에는 표시를 남기지 않는다 — 시연용 표시가 포장 화면이 읽는 정보에
 * 섞이지 않아야 한다. 리셋이 이 표를 비우면 처음부터 다시 나온다.
 */
@Entity
@Table(name = "demo_served_tote")
public class DemoServedTote {

    @Id
    @Column(name = "shipment_id")
    private Long shipmentId;

    @Column(name = "served_at", nullable = false)
    private LocalDateTime servedAt;

    protected DemoServedTote() {
    }

    public DemoServedTote(Long shipmentId) {
        this.shipmentId = shipmentId;
        this.servedAt = LocalDateTime.now();
    }

    public Long shipmentId() {
        return shipmentId;
    }
}
