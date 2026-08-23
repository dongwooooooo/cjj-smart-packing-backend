package com.awesome.backend.outbound.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByOrderIdOrderBySeqNoAsc(Long orderId);
}
