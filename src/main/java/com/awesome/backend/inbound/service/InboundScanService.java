package com.awesome.backend.inbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.controller.ProductSummary;
import com.awesome.backend.inbound.controller.ScanResponse;
import com.awesome.backend.inbound.entity.Category;
import com.awesome.backend.inbound.entity.KoreanNetMaster;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.CategoryRepository;
import com.awesome.backend.inbound.repository.KoreanNetMasterRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 바코드 스캔 판정 (docs/02-api-spec.md 1-1). */
@Service
public class InboundScanService {

    private final ProductRepository productRepository;
    private final KoreanNetMasterRepository koreanNetMasterRepository;
    private final CategoryRepository categoryRepository;
    private final MasterImageSource masterImages;

    public InboundScanService(ProductRepository productRepository,
                              KoreanNetMasterRepository koreanNetMasterRepository,
                              CategoryRepository categoryRepository, MasterImageSource masterImages) {
        this.productRepository = productRepository;
        this.koreanNetMasterRepository = koreanNetMasterRepository;
        this.categoryRepository = categoryRepository;
        this.masterImages = masterImages;
    }

    /**
     * 3분기 판정 (1-1).
     *
     * <p>NEW 분기에서는 이 시점에 product 를 생성한다 — 측정 세션이 product 를 FK 로 물고
     * 생성되므로 순서가 항상 product → measurement_session 이어야 하기 때문이다 (docs/03 §3).
     *
     * <p>UNKNOWN 은 여기서 끝난다. 마스터에 없는 바코드는 입고 대상이 아니므로 수기 등록
     * 경로를 두지 않는다 (D-21).
     */
    @Transactional
    public ScanResponse scan(String barcode) {
        Optional<Product> existing = productRepository.findByGtin(barcode);
        if (existing.isPresent()) {
            Product product = existing.get();
            ScanJudgment judgment = product.hasConfirmedDimensions()
                    ? ScanJudgment.REGISTERED
                    : ScanJudgment.NEW;
            return ScanResponse.of(judgment, ProductSummary.from(product, categoryOf(product), masterImages.urlFor(product)));
        }

        return koreanNetMasterRepository.findByGtin(barcode)
                .map(master -> {
                    Product created = productRepository.save(
                            Product.fromMaster(master, resolveImageUrl(master)));
                    return ScanResponse.of(ScanJudgment.NEW, ProductSummary.from(created, categoryOf(created), masterImages.urlFor(created)));
                })
                .orElseGet(ScanResponse::unknown);
    }

    /** 마스터에 이미지가 없으면 대체 주소를 쓴다 — product.image_url 이 NOT NULL 이라 값이 필요하다. */
    private String resolveImageUrl(KoreanNetMaster master) {
        String imageUrl = master.getImageUrl();
        return (imageUrl == null || imageUrl.isBlank()) ? Product.PLACEHOLDER_IMAGE_URL : imageUrl;
    }

    /**
     * Product 는 medium_category_code 만 값으로 들고 있어서, 이름이 필요하면 여기서 조회한다.
     * 분류는 1-1 응답의 표시 전용이다 — 작업자가 고르는 UI 는 없다 (D-21).
     */
    private Category categoryOf(Product product) {
        return categoryRepository.findById(product.mediumCategoryCode())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "상품의 분류 코드가 존재하지 않습니다.",
                        Map.of("mediumCategoryCode", product.mediumCategoryCode())));
    }
}
