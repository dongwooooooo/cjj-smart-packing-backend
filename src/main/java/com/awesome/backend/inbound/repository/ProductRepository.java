package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByGtin(String gtin);

    /** 편성 입력용 — 배치의 GTIN들을 쿼리 1번으로 조회. */
    List<Product> findByGtinIn(Collection<String> gtins);

    /** 배치 접수 검증용 — 넘긴 바코드 중 상품 마스터에 있는 것만 돌려준다. */
    @Query("select p.gtin from Product p where p.gtin in :gtins")
    List<String> findKnownGtins(@Param("gtins") Collection<String> gtins);

    /** stock-in(1-5)의 productId → gtin 변환. 재고 창구가 gtin 을 받기 때문에 필요하다. */
    @Query("select p.gtin from Product p where p.id = :id")
    Optional<String> findGtinById(@Param("id") Long id);
}
