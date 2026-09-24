package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.BoxType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BoxTypeRepository extends JpaRepository<BoxType, Long> {

    /**
     * 박스 재고 차감용 조회 — 행 잠금(SELECT FOR UPDATE)으로 가져온다. 영속성 컨텍스트에
     * 잠금 전 인스턴스가 남지 않도록 엔티티를 먼저 로드하지 않는다 — 동시에 여러 포장완료
     * 요청이 같은 박스 재고를 깎을 때 lost update를 막기 위해서다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BoxType b where b.id = :id")
    Optional<BoxType> findByIdForUpdate(@Param("id") Long id);
}
