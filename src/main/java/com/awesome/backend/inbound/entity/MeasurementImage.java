package com.awesome.backend.inbound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 촬영 이미지 — 고정 카메라 3대가 만든 1세트 (docs/03-erd.md §3).
 *
 * <p>출고 포장 화면의 제품 이미지 조회(1-6)는 해당 product 의 CONFIRMED 세션 이미지를 쓰고,
 * 수기 확정처럼 이미지가 없는 경로는 코리안넷 image_url 로 대체한다.
 */
@Entity
@Table(name = "measurement_image",
        uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "camera_no"}))
public class MeasurementImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private MeasurementSession session;

    /** 카메라 번호 1~3. */
    @Column(name = "camera_no", nullable = false)
    private Short cameraNo;

    @Column(name = "file_path", length = 500, nullable = false)
    private String filePath;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected MeasurementImage() {
    }

    MeasurementImage(MeasurementSession session, short cameraNo, String filePath) {
        this.session = session;
        this.cameraNo = cameraNo;
        this.filePath = filePath;
    }

    public Long getId() {
        return id;
    }

    public MeasurementSession getSession() {
        return session;
    }

    public Short getCameraNo() {
        return cameraNo;
    }

    public String getFilePath() {
        return filePath;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
