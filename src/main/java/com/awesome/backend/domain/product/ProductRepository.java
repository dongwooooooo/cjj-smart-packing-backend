package com.awesome.backend.domain.product;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * product 조회. 쓰기는 P1 전용이며, P2·P3 은 반드시
 * {@code @Transactional(readOnly = true)} 안에서 읽는다 (docs/05 §3).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 바코드 스캔(1-1)의 진입 조회. */
    Optional<Product> findByGtin(String gtin);

    boolean existsByGtin(String gtin);
}
