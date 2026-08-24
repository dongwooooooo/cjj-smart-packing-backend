package com.awesome.backend.inbound.measurement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeasurementSessionRepository extends JpaRepository<MeasurementSession, Long> {

    /** 재촬영(1-3 재호출) 시 DISCARDED 로 정리할 이전 세션들. */
    List<MeasurementSession> findByProductIdAndStatusIn(Long productId, List<MeasurementStatus> statuses);

    /** 제품 이미지 조회(1-6) — 가장 최근 확정 세션. */
    Optional<MeasurementSession> findFirstByProductIdAndStatusOrderByConfirmedAtDesc(
            Long productId, MeasurementStatus status);
}
