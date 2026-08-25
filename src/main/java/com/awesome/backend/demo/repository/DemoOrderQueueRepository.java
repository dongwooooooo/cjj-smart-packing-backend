package com.awesome.backend.demo.repository;

import com.awesome.backend.demo.entity.DemoOrderQueue;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoOrderQueueRepository extends JpaRepository<DemoOrderQueue, Long> {

    /** 다음에 투입할 배치 — 아직 안 나간 것 중 순번이 가장 앞선 것. */
    Optional<DemoOrderQueue> findFirstByRunIdAndReleasedAtIsNullOrderBySeqAsc(String runId);

    int countByRunIdAndReleasedAtIsNull(String runId);

    boolean existsByRunId(String runId);
}
