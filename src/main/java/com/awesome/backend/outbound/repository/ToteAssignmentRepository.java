package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.ToteAssignment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToteAssignmentRepository extends JpaRepository<ToteAssignment, Long> {

    Optional<ToteAssignment> findByToteIdAndReleasedAtIsNull(Long toteId);

    Optional<ToteAssignment> findByShipmentIdAndReleasedAtIsNull(Long shipmentId);
}
