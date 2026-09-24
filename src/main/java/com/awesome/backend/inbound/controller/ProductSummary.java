package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.entity.Category;
import com.awesome.backend.inbound.entity.Product;

/**
 * 스캔 1단 표시 데이터 (1-1 응답의 product).
 *
 * <p>{@code categoryL} 은 저장된 값이 아니라 중분류의 parent_code 를 타고 조회한 결과다 —
 * 대분류를 중복 저장하면 계층이 어긋날 수 있어 ERD 가 그렇게 설계돼 있다 (docs/03 §2).
 * Product 는 medium_category_code 만 값으로 들고 있어서, 이름 조회는 호출부가 Category 를
 * 따로 넘겨준다.
 */
public record ProductSummary(
        Long productId,
        String gtin,
        String name,
        String categoryL,
        String categoryM,
        String imageUrl,
        String dimStatus,
        int stockQty) {

    public static ProductSummary from(Product product, Category category, String imageUrl, int onHandQty) {
        return new ProductSummary(
                product.id(), product.gtin(), product.name(),
                category.getLargeName(), category.getName(),
                imageUrl, product.dimStatus(), onHandQty);
    }
}
