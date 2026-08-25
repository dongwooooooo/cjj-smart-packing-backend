package com.awesome.backend.outbound.entity;

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
 * 배송단위 — 박스 1개 = 토트 1개. 테이블 주인은 P2, 생성은 P3 출고지시 import.
 * 필드는 V1 스키마 그대로 — 임의 확장하지 않는다 (패키지 경계 합의).
 */
@Entity
@Table(name = "shipment")
public class Shipment {

    public enum Status { PLANNED, TOTE_ASSIGNED, PACKING, PACKED, LOADED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @Column(name = "line_id", nullable = false)
    private Long lineId;

    @Column(name = "recommended_box_id", nullable = false)
    private Long recommendedBoxId;

    @Column(name = "final_box_id")
    private Long finalBoxId;

    @Column(name = "filler_recommended", nullable = false)
    private boolean fillerRecommended;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "packed_at")
    private LocalDateTime packedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Shipment() {
    }

    public Shipment(Long orderId, int seqNo, Long lineId, Long recommendedBoxId, boolean fillerRecommended) {
        this.orderId = orderId;
        this.seqNo = seqNo;
        this.lineId = lineId;
        this.recommendedBoxId = recommendedBoxId;
        this.fillerRecommended = fillerRecommended;
        this.status = Status.PLANNED;
        this.createdAt = LocalDateTime.now();
    }

    public Long id() {
        return id;
    }

    public Long orderId() {
        return orderId;
    }

    public Status status() {
        return status;
    }

    public int seqNo() {
        return seqNo;
    }

    public Long lineId() {
        return lineId;
    }

    public Long recommendedBoxId() {
        return recommendedBoxId;
    }

    public Long finalBoxId() {
        return finalBoxId;
    }

    public boolean fillerRecommended() {
        return fillerRecommended;
    }

    public void assignTote() {
        this.status = Status.TOTE_ASSIGNED;
    }

    /**
     * TOTE_ASSIGNED → PACKING 전이 (POST /totes/scan, docs/02-api-spec.md 3-5, D-14).
     * 이미 PACKING이면 재스캔 멱등 처리로 아무것도 하지 않는다. 그 외 상태(PLANNED/PACKED/LOADED)는
     * 활성 tote_assignment가 있는데도 아직 포장을 시작하지 않았거나 이미 끝난 것이므로 데이터
     * 정합성이 깨진 경우다 — 호출자(ToteScanService)가 잡아 INVALID_STATE로 변환한다.
     */
    public void startPacking() {
        if (status == Status.TOTE_ASSIGNED) {
            this.status = Status.PACKING;
            return;
        }
        if (status != Status.PACKING) {
            throw new IllegalStateException("PACKING으로 전이할 수 없는 상태입니다: " + status);
        }
    }

    /**
     * 박스 오버라이드 (PUT /shipments/{shipmentId}/box, docs/02-api-spec.md 3-3). 상태 제약 없이
     * 무조건 세팅한다 — assignTote()와 같은 무조건부 스타일.
     */
    public void overrideBox(Long boxTypeId) {
        this.finalBoxId = boxTypeId;
    }
}
