package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByGtin(String gtin);

    /** 편성 입력용 — 배치의 GTIN들을 쿼리 1번으로 조회. */
    List<Product> findByGtinIn(Collection<String> gtins);

    /** 배치 접수 검증용 — 넘긴 바코드 중 상품 마스터에 있는 것만 돌려준다. */
    @Query("select p.gtin from Product p where p.gtin in :gtins")
    List<String> findKnownGtins(@Param("gtins") Collection<String> gtins);

    /**
     * stock-in(1-5)의 productId → gtin 변환. 재고 창구가 gtin 을 받기 때문에 필요하다.
     *
     * <p>엔티티가 아니라 값만 뽑는 것이 핵심이다 — 창구를 부르기 전에 Product 를 로드해두면
     * {@link #findByGtinForUpdate} 가 SELECT FOR UPDATE 를 실행해도 영속성 컨텍스트에 이미
     * 있는 인스턴스(잠금 전 stock_qty)를 돌려주므로, 행 잠금이 lost update 를 못 막는다.
     */
    @Query("select p.gtin from Product p where p.id = :id")
    Optional<String> findGtinById(@Param("id") Long id);

    /**
     * 재고 증감용 조회 — 행 잠금(SELECT FOR UPDATE).
     * 두 트랜잭션이 같은 상품의 stock_qty를 동시에 read-modify-write 하면
     * 나중 커밋이 먼저 커밋을 덮어쓰므로(lost update), 쓰기 경로는 반드시 이걸 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.gtin = :gtin")
    Optional<Product> findByGtinForUpdate(@Param("gtin") String gtin);
}
