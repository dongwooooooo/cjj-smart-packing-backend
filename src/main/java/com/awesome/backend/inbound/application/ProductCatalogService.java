package com.awesome.backend.inbound.application;

import com.awesome.backend.inbound.domain.ProductRepository;
import com.awesome.backend.orders.application.ProductCatalog;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P1 인수 대상 — 이 어댑터는 P3가 임시 작성했다. P1이 자기 방식으로 재구현해도
 * orders.application.ProductCatalog 계약만 지키면 된다.
 */
@Service
public class ProductCatalogService implements ProductCatalog {

    private final ProductRepository productRepository;

    public ProductCatalogService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findKnownGtins(List<String> gtins) {
        if (gtins.isEmpty()) {
            return List.of();
        }
        return productRepository.findKnownGtins(gtins);
    }
}
