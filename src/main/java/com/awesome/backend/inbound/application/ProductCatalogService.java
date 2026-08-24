package com.awesome.backend.inbound.application;

import com.awesome.backend.inbound.domain.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
