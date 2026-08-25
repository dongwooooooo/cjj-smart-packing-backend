package com.awesome.backend.demo.repository;

import com.awesome.backend.demo.entity.DemoProduct;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoProductRepository extends JpaRepository<DemoProduct, String> {

    List<DemoProduct> findByPool(DemoProduct.Pool pool);
}
