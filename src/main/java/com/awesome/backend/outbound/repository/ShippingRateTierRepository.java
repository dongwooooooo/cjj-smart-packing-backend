package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.ShippingRateTier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingRateTierRepository extends JpaRepository<ShippingRateTier, Long> {
}
