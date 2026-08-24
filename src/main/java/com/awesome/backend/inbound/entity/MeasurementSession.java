package com.awesome.backend.inbound.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 촬영·추론 세션 (docs/03-erd.md §3).
 *
 * <p>추론이 동기 응답이므로 세션은 항상 결과 상태(INFERRED 또는 MEASURE_FAILED)로 생성된다.
 * 생성 순서는 항상 product → measurement_session 이다.
 *
 * <p>무게는 추론과 별개 경로라 MEASURE_FAILED 여도 값이 있을 수 있고, 신뢰도 게이트의
 * 대상이 아니다 (D-02). 시연에서는 저울 대신 product 사전 등록값을 쓴다 (D-10).
 */
@Entity
@Table(name = "measurement_session")
public class MeasurementSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private MeasurementStatus status;

    /** 추론 치수. 축 규약(D-18)에 맞춰 가로·세로를 정렬한 뒤 저장한다 (02 §1-3). */
    @Column(name = "inferred_width_cm", precision = 5, scale = 1)
    private BigDecimal inferredWidthCm;

    @Column(name = "inferred_length_cm", precision = 5, scale = 1)
    private BigDecimal inferredLengthCm;

    @Column(name = "inferred_height_cm", precision = 5, scale = 1)
    private BigDecimal inferredHeightCm;

    /** 촬영 시점 저울 연동값. 추론 실패와 무관하게 들어올 수 있다. */
    @Column(name = "measured_weight_kg", precision = 6, scale = 3)
    private BigDecimal measuredWeightKg;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    /** 임계값 통과 ∧ 종횡비 정상 ∧ 상한 이내. 치수 추론에만 적용되며 무게와는 무관하다. */
    @Column(name = "gate_passed", nullable = false)
    private boolean gatePassed;

    /** 예: ["LOW_CONFIDENCE", "ASPECT_RATIO"] — 승인 버튼 비활성 사유 표시용. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_fail_reasons")
    private List<String> gateFailReasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirm_method", length = 10)
    private ConfirmMethod confirmMethod;

    /** 확정값. 축 규약(D-18)에 맞춰 정렬된 상태로 저장된다. */
    @Column(name = "confirmed_width_cm", precision = 6, scale = 1)
    private BigDecimal confirmedWidthCm;

    @Column(name = "confirmed_length_cm", precision = 6, scale = 1)
    private BigDecimal confirmedLengthCm;

    @Column(name = "confirmed_height_cm", precision = 6, scale = 1)
    private BigDecimal confirmedHeightCm;

    @Column(name = "confirmed_weight_kg", precision = 7, scale = 3)
    private BigDecimal confirmedWeightKg;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("cameraNo ASC")
    private List<MeasurementImage> images = new ArrayList<>();

    protected MeasurementSession() {
    }

    private MeasurementSession(Product product, MeasurementStatus status) {
        this.product = product;
        this.status = status;
    }

    /** 추론 성공 세션. */
    public static MeasurementSession inferred(Product product, BigDecimal widthCm, BigDecimal lengthCm,
                                              BigDecimal heightCm, BigDecimal measuredWeightKg,
                                              BigDecimal confidence, boolean gatePassed,
                                              List<String> gateFailReasons) {
        MeasurementSession session = new MeasurementSession(product, MeasurementStatus.INFERRED);
        session.inferredWidthCm = widthCm;
        session.inferredLengthCm = lengthCm;
        session.inferredHeightCm = heightCm;
        session.measuredWeightKg = measuredWeightKg;
        session.confidence = confidence;
        session.gatePassed = gatePassed;
        session.gateFailReasons = gateFailReasons;
        return session;
    }

    /** 타임아웃·실패 세션. 저울값은 수집됐으면 담는다. */
    public static MeasurementSession failed(Product product, BigDecimal measuredWeightKg) {
        MeasurementSession session = new MeasurementSession(product, MeasurementStatus.MEASURE_FAILED);
        session.measuredWeightKg = measuredWeightKg;
        session.gatePassed = false;
        return session;
    }

    /** 촬영 이미지 1장을 붙인다. */
    public void addImage(short cameraNo, String filePath) {
        images.add(new MeasurementImage(this, cameraNo, filePath));
    }

    /**
     * 확정 처리 (1-4). 가로·세로는 축 규약(D-18)에 맞춰 정렬해 기록한다.
     * 상태 검증(게이트·중복 확정)은 서비스에서 수행한다.
     */
    public void confirm(ConfirmMethod method, BigDecimal widthCm, BigDecimal lengthCm,
                        BigDecimal heightCm, BigDecimal weightKg) {
        boolean swap = widthCm.compareTo(lengthCm) < 0;
        this.confirmedWidthCm = swap ? lengthCm : widthCm;
        this.confirmedLengthCm = swap ? widthCm : lengthCm;
        this.confirmedHeightCm = heightCm;
        this.confirmedWeightKg = weightKg;
        this.confirmMethod = method;
        this.status = MeasurementStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /** 재촬영 시 이전 세션을 폐기한다. */
    public void discard() {
        this.status = MeasurementStatus.DISCARDED;
    }

    /** 확정·폐기되지 않아 아직 확정 가능한 세션인지. */
    public boolean isOpen() {
        return status == MeasurementStatus.INFERRED || status == MeasurementStatus.MEASURE_FAILED;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public MeasurementStatus getStatus() {
        return status;
    }

    public BigDecimal getInferredWidthCm() {
        return inferredWidthCm;
    }

    public BigDecimal getInferredLengthCm() {
        return inferredLengthCm;
    }

    public BigDecimal getInferredHeightCm() {
        return inferredHeightCm;
    }

    public BigDecimal getMeasuredWeightKg() {
        return measuredWeightKg;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public boolean isGatePassed() {
        return gatePassed;
    }

    public List<String> getGateFailReasons() {
        return gateFailReasons == null ? List.of() : Collections.unmodifiableList(gateFailReasons);
    }

    public ConfirmMethod getConfirmMethod() {
        return confirmMethod;
    }

    public BigDecimal getConfirmedWidthCm() {
        return confirmedWidthCm;
    }

    public BigDecimal getConfirmedLengthCm() {
        return confirmedLengthCm;
    }

    public BigDecimal getConfirmedHeightCm() {
        return confirmedHeightCm;
    }

    public BigDecimal getConfirmedWeightKg() {
        return confirmedWeightKg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public List<MeasurementImage> getImages() {
        return Collections.unmodifiableList(images);
    }
}
