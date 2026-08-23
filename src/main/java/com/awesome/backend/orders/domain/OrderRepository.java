package com.awesome.backend.orders.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    boolean existsByReceiptNo(String receiptNo);

    /** 배치 접수 전 재전송 검사용 — 주문번호 목록 중 이미 저장된 것만 돌려준다. */
    @Query("select o.receiptNo from Order o where o.receiptNo in :receiptNos")
    List<String> findExistingReceiptNos(@Param("receiptNos") Collection<String> receiptNos);
}
