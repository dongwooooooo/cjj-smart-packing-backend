package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.ToteAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToteAssignmentRepository extends JpaRepository<ToteAssignment, Long> {

    Optional<ToteAssignment> findByToteIdAndReleasedAtIsNull(Long toteId);

    Optional<ToteAssignment> findByShipmentIdAndReleasedAtIsNull(Long shipmentId);

    /** 라인 shipment 목록 조회용 배치 조회 — shipment마다 개별 쿼리(N+1)를 피한다. */
    List<ToteAssignment> findByShipmentIdInAndReleasedAtIsNull(List<Long> shipmentIds);
}
