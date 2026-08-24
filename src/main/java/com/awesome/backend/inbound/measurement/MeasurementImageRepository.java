package com.awesome.backend.inbound.measurement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementImageRepository extends JpaRepository<MeasurementImage, Long> {

    List<MeasurementImage> findBySessionIdOrderByCameraNoAsc(Long sessionId);
}
