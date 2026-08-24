package com.awesome.backend.inbound.scan;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.domain.product.Category;
import com.awesome.backend.domain.product.CategoryLevel;
import com.awesome.backend.domain.product.CategoryRepository;
import com.awesome.backend.domain.product.KoreanNetMaster;
import com.awesome.backend.domain.product.KoreanNetMasterRepository;
import com.awesome.backend.domain.product.Product;
import com.awesome.backend.domain.product.ProductRepository;
import com.awesome.backend.inbound.scan.dto.ManualProductRequest;
import com.awesome.backend.inbound.scan.dto.ProductSummary;
import com.awesome.backend.inbound.scan.dto.ScanResponse;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 바코드 스캔 판정과 임시 마스터 생성 (docs/02-api-spec.md 1-1, 1-2). */
@Service
public class InboundScanService {

    private final ProductRepository productRepository;
    private final KoreanNetMasterRepository koreanNetMasterRepository;
    private final CategoryRepository categoryRepository;

    public InboundScanService(ProductRepository productRepository,
                              KoreanNetMasterRepository koreanNetMasterRepository,
                              CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.koreanNetMasterRepository = koreanNetMasterRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * 3분기 판정 (1-1).
     *
     * <p>NEW 분기에서는 이 시점에 product 를 생성한다 — 측정 세션이 product 를 FK 로 물고
     * 생성되므로 순서가 항상 product → measurement_session 이어야 하기 때문이다 (docs/03 §3).
     */
    @Transactional
    public ScanResponse scan(String barcode) {
        Optional<Product> existing = productRepository.findByGtin(barcode);
        if (existing.isPresent()) {
            Product product = existing.get();
            ScanJudgment judgment = product.hasConfirmedDimensions()
                    ? ScanJudgment.REGISTERED
                    : ScanJudgment.NEW;
            return ScanResponse.of(judgment, ProductSummary.from(product));
        }

        return koreanNetMasterRepository.findByGtin(barcode)
                .map(master -> {
                    Product created = productRepository.save(
                            Product.fromMaster(master, resolveImageUrl(master)));
                    return ScanResponse.of(ScanJudgment.NEW, ProductSummary.from(created));
                })
                .orElseGet(ScanResponse::unknown);
    }

    /** 미등록 바코드의 임시 마스터 생성 (1-2). source=MANUAL, dimStatus=NONE 으로 시작한다. */
    @Transactional
    public ProductSummary createManual(ManualProductRequest request) {
        if (productRepository.existsByGtin(request.gtin())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "이미 등록된 바코드입니다.", Map.of("gtin", request.gtin()));
        }

        Category category = categoryRepository.findById(request.mediumCategoryCode())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "존재하지 않는 분류 코드입니다.",
                        Map.of("mediumCategoryCode", request.mediumCategoryCode())));

        if (category.getLevel() != CategoryLevel.MEDIUM) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "중분류 코드를 지정해야 합니다.",
                    Map.of("mediumCategoryCode", request.mediumCategoryCode(),
                            "level", category.getLevel().name()));
        }

        Product product = productRepository.save(
                Product.manual(request.gtin(), request.name(), category, Product.PLACEHOLDER_IMAGE_URL));
        return ProductSummary.from(product);
    }

    /** 마스터에 이미지가 없으면 대체 주소를 쓴다 — product.image_url 이 NOT NULL 이라 값이 필요하다. */
    private String resolveImageUrl(KoreanNetMaster master) {
        String imageUrl = master.getImageUrl();
        return (imageUrl == null || imageUrl.isBlank()) ? Product.PLACEHOLDER_IMAGE_URL : imageUrl;
    }
}
