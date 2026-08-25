package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoProduct;
import java.math.BigDecimal;
import java.util.List;

/**
 * products.json 한 항목 (명세 §2). 파일 형식을 그대로 옮긴 값이 아니라,
 * 검증과 축 정렬을 끝낸 상태다 — 읽는 쪽은 값이 온전하다고 보고 쓰면 된다.
 *
 * <p>치수는 두 풀 모두 들어온다. OUTBOUND는 상품 확정치로, INBOUND는 정답치로 쓰인다.
 */
public record DemoProductSpec(
        String gtin,
        String name,
        String mediumCategoryCode,
        DemoProduct.Pool pool,
        BigDecimal widthCm,
        BigDecimal lengthCm,
        BigDecimal heightCm,
        BigDecimal weightKg,
        boolean refrigerate,
        boolean fragile,
        boolean irregular,
        int stockQty,
        List<String> images) {

    /** 이미지가 담긴 디렉토리. demo_product.image_dir에 그대로 들어간다. */
    public String imageDir() {
        if (images.isEmpty()) {
            return null;
        }
        String first = images.getFirst();
        int lastSlash = first.lastIndexOf('/');
        return lastSlash < 0 ? null : first.substring(0, lastSlash);
    }
}
