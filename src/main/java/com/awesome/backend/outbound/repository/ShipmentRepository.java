package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.Shipment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    List<Shipment> findByOrderIdOrderBySeqNoAsc(Long orderId);

    /**
     * 라인별 조회 (GET /lines/{lineId}/shipments, docs/02-api-spec.md 3-1). status는
     * 선택 필터라 두 메서드로 나눠 DB 레벨에서 필터링한다 — 전체를 가져와 서비스에서 걸러내는
     * 것보다 불필요한 row를 애초에 안 읽는다. 정렬은 createdAt 오름차순(포장 대기열 선입선출)에
     * id를 타이브레이커로 둔다 — 배치 import처럼 createdAt이 동일 순간에 몰릴 때도 순서가
     * 안정적이도록.
     */
    List<Shipment> findByLineIdOrderByCreatedAtAscIdAsc(Long lineId);

    List<Shipment> findByLineIdAndStatusOrderByCreatedAtAscIdAsc(Long lineId, Shipment.Status status);

    /**
     * 3-8 포장 완료 응답의 line.packedCount — 그 라인에서 지금까지 PACKED로 전이된 배송단위 수.
     * 같은 트랜잭션에서 방금 PACKED로 바꾼 shipment도 flush 후 반영돼 즉시 셈에 포함된다.
     */
    long countByLineIdAndStatus(Long lineId, Shipment.Status status);
}
