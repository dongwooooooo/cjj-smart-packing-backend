package com.awesome.backend.inbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.controller.ProductImagesResponse;
import com.awesome.backend.inbound.entity.MeasurementImage;
import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.entity.MeasurementStatus;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제품 원본 이미지 조회 (02 §1-6). 출고 포장 화면(P2)이 소비하는 계약이다.
 *
 * <p>확정 세션의 촬영 3장을 우선 쓰고, 없으면 코리안넷 이미지로 대체한다 —
 * 수기 확정(1-4 MANUAL)은 촬영을 거치지 않아 이미지가 없을 수 있고,
 * MEASURE_FAILED 세션을 수기 확정한 경우도 마찬가지다.
 */
@Service
public class ProductImageService {

    private final ProductRepository productRepository;
    private final MeasurementSessionRepository sessionRepository;
    private final MeasurementImageSource imageSource;

    public ProductImageService(ProductRepository productRepository,
                               MeasurementSessionRepository sessionRepository,
                               MeasurementImageSource imageSource) {
        this.productRepository = productRepository;
        this.sessionRepository = sessionRepository;
        this.imageSource = imageSource;
    }

    @Transactional(readOnly = true)
    public ProductImagesResponse images(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));

        return confirmedImages(productId)
                .map(images -> ProductImagesResponse.ofMeasurement(images, imageSource::url))
                // product.image_url 은 스캔 시 마스터에서 복사한 값이라 NOT NULL 이 보장된다.
                // 마스터에 이미지가 없었으면 placeholder 가 들어 있다.
                .orElseGet(() -> ProductImagesResponse.ofMasterFallback(product.imageUrl()));
    }

    /**
     * 가장 최근 확정 세션의 촬영 이미지. 세션이 없거나 이미지가 비어 있으면 비어 있는 Optional 이다 —
     * 둘 다 호출자 입장에서는 "촬영본 없음"으로 같다.
     */
    private Optional<List<MeasurementImage>> confirmedImages(Long productId) {
        return sessionRepository
                .findFirstByProductIdAndStatusOrderByConfirmedAtDesc(productId, MeasurementStatus.CONFIRMED)
                .map(MeasurementSession::getImages)
                .filter(images -> !images.isEmpty());
    }
}
