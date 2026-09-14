package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.MeasurementImage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementImageRepository extends JpaRepository<MeasurementImage, Long> {

    List<MeasurementImage> findBySessionIdOrderByCameraNoAsc(Long sessionId);

    /** (session_id, camera_no) 는 유일 제약이라 행이 한 개다. */
    Optional<MeasurementImage> findBySessionIdAndCameraNo(Long sessionId, short cameraNo);
}
