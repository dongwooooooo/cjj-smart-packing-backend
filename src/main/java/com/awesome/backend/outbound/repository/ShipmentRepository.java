package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.Shipment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByOrderIdOrderBySeqNoAsc(Long orderId);
}
