package com.awesome.backend.inbound.entity;

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

    /**
     * 보관소 업로드 상태. 업로드는 세션 커밋 뒤에 일어나므로 행이 먼저 생기고 객체가 나중에 생긴다.
     * 1-6 조회는 STORED 인 행만 쓴다 — 없는 객체의 임시 주소를 내보내지 않기 위해서다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", length = 10, nullable = false)
    private ImageUploadStatus uploadStatus = ImageUploadStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected MeasurementImage() {
    }

    MeasurementImage(MeasurementSession session, short cameraNo, String filePath) {
        this.session = session;
        this.cameraNo = cameraNo;
        this.filePath = filePath;
        this.uploadStatus = ImageUploadStatus.PENDING;
    }

    /** 업로드가 끝났다 — 이제 조회 주소를 발급해도 된다. */
    public void markStored() {
        this.uploadStatus = ImageUploadStatus.STORED;
    }

    /** 재시도까지 실패했다. 행은 남겨 둔다 — 어느 사진이 빠졌는지 알 수 있어야 한다. */
    public void markUploadFailed() {
        this.uploadStatus = ImageUploadStatus.FAILED;
    }

    /** 보관소에 객체가 있는지. */
    public boolean isStored() {
        return uploadStatus == ImageUploadStatus.STORED;
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

    public ImageUploadStatus getUploadStatus() {
        return uploadStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
