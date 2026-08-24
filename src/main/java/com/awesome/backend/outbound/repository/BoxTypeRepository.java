package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.BoxType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoxTypeRepository extends JpaRepository<BoxType, Long> {
}
