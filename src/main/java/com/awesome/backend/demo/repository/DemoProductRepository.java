package com.awesome.backend.demo.repository;

import com.awesome.backend.demo.entity.DemoProduct;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoProductRepository extends JpaRepository<DemoProduct, String> {

    List<DemoProduct> findByPool(DemoProduct.Pool pool);

    /** 아직 내주지 않은 것만, 바코드 순. 시연 순서가 매번 같도록 정렬한다. */
    List<DemoProduct> findByPoolAndServedAtIsNullOrderByGtinAsc(DemoProduct.Pool pool);
}
