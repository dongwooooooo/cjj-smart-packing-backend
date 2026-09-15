package com.awesome.backend.inbound.service;

import com.awesome.backend.inbound.entity.MeasurementImage;
import com.awesome.backend.inbound.repository.MeasurementImageRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드 결과를 measurement_image 행에 적는다 (D-27).
 *
 * <p>{@link MeasurementImageUploader} 와 분리한 이유는 트랜잭션 경계다. 업로드는 커밋 뒤에
 * 트랜잭션 없이 돌고, 상태 표시만 짧은 트랜잭션으로 쓴다. 같은 빈 안의 메서드 호출은 프록시를
 * 타지 않아 {@code @Transactional} 이 걸리지 않으므로 별도 빈이어야 한다.
 */
@Component
public class MeasurementImageUploadState {

    private final MeasurementImageRepository imageRepository;

    public MeasurementImageUploadState(MeasurementImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @Transactional
    public void markStored(Long sessionId, short cameraNo) {
        find(sessionId, cameraNo).ifPresent(MeasurementImage::markStored);
    }

    @Transactional
    public void markFailed(Long sessionId, short cameraNo) {
        find(sessionId, cameraNo).ifPresent(MeasurementImage::markUploadFailed);
    }

    /** 세션·카메라 번호는 measurement_image 의 유일 제약이라 행이 한 개다. */
    private Optional<MeasurementImage> find(Long sessionId, short cameraNo) {
        return imageRepository.findBySessionIdAndCameraNo(sessionId, cameraNo);
    }
}
