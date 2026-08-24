package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.MeasurementImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementImageRepository extends JpaRepository<MeasurementImage, Long> {

    List<MeasurementImage> findBySessionIdOrderByCameraNoAsc(Long sessionId);
}
