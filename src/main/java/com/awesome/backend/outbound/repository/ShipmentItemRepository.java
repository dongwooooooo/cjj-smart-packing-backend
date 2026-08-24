package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentItemRepository extends JpaRepository<ShipmentItem, Long> {

    List<ShipmentItem> findByShipmentId(Long shipmentId);

    /**
     * 약속(soft allocation) 수량 — 포장이 끝나지 않은 배송단위에 담긴 수량 합.
     * 가용재고 = stock_qty − 이 값 (concepts/inventory-allocation).
     */
    @Query("""
            select coalesce(sum(si.qty), 0) from ShipmentItem si
            join Shipment s on s.id = si.shipmentId
            where si.productId = :productId and s.status in :statuses
            """)
    int sumQtyByProductAndStatuses(@Param("productId") Long productId,
                                   @Param("statuses") List<Shipment.Status> statuses);

    /**
     * 약속(soft allocation) 수량 — 포장이 끝나지 않은 배송단위에 담긴 수량 합.
     * 가용재고 = stock_qty − 이 값 (concepts/inventory-allocation).
     */
    default int allocatedQty(Long productId) {
        return sumQtyByProductAndStatuses(productId,
                List.of(Shipment.Status.PLANNED, Shipment.Status.TOTE_ASSIGNED, Shipment.Status.PACKING));
    }
}
