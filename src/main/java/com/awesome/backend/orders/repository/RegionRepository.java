package com.awesome.backend.orders.repository;

import com.awesome.backend.orders.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, String> {
}
