package com.awesome.backend.demo.repository;

import com.awesome.backend.demo.entity.DemoServedTote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoServedToteRepository extends JpaRepository<DemoServedTote, Long> {

    @Override
    List<DemoServedTote> findAll();
}
