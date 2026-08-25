package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제품 원본 이미지 조회 (docs/02-api-spec.md 1-6).
 *
 * <p>경로가 {@code /inbound} 밑이 아니다 — 소비자가 출고 포장 화면(P2)이라 입고 전용 경로에 두지 않는다.
 */
@Tag(name = "제품")
@RestController
@RequestMapping("/api/v1/products")
public class ProductImageController {

    private final ProductImageService productImageService;

    public ProductImageController(ProductImageService productImageService) {
        this.productImageService = productImageService;
    }

    @Operation(summary = "제품 원본 이미지",
            description = "확정 세션의 촬영 3장을 돌려준다(source=MEASUREMENT). 수기 확정 등으로 촬영본이 "
                    + "없으면 코리안넷 이미지 1장으로 대체하며, 그때 cameraNo 는 null 이다"
                    + "(source=MASTER_FALLBACK).")
    @GetMapping("/{productId}/images")
    public ProductImagesResponse images(@PathVariable Long productId) {
        return productImageService.images(productId);
    }
}
