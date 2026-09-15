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
 * <p>확정 세션의 촬영 3장을 우선 쓰고, 없으면 상품 사진 한 장으로 대체한다 —
 * 수기 확정(1-4 MANUAL)은 촬영을 거치지 않아 이미지가 없을 수 있고,
 * MEASURE_FAILED 세션을 수기 확정한 경우도 마찬가지다.
 *
 * <p>대체 사진은 스캔 1단이 쓰는 것과 같은 출처를 탄다. 출고 시연 상품은 촬영을 거치지 않아
 * 늘 이 길로 오는데, 상품에 적힌 주소만 쓰면 사진이 없다고 나온다 — 실제 사진은 저장소에
 * 올려 둔 것이다.
 *
 * <p>업로드가 아직 끝나지 않았거나(PENDING) 실패한(FAILED) 사진은 목록에서 뺀다 (D-27).
 * 보관소에 없는 객체의 임시 주소를 내보내면 화면은 깨진 이미지를 그린다. 한 장도 남지 않으면
 * 촬영본이 없는 것과 같게 취급해 마스터 대체 사진으로 간다 — 응답 형태는 그대로다.
 */
@Service
public class ProductImageService {

    private final ProductRepository productRepository;
    private final MeasurementSessionRepository sessionRepository;
    private final MeasurementImageSource imageSource;
    private final MasterImageSource masterImageSource;

    public ProductImageService(ProductRepository productRepository,
                               MeasurementSessionRepository sessionRepository,
                               MeasurementImageSource imageSource,
                               MasterImageSource masterImageSource) {
        this.productRepository = productRepository;
        this.sessionRepository = sessionRepository;
        this.imageSource = imageSource;
        this.masterImageSource = masterImageSource;
    }

    @Transactional(readOnly = true)
    public ProductImagesResponse images(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));

        return storedConfirmedImages(productId)
                .map(images -> ProductImagesResponse.ofMeasurement(images, imageSource::url))
                // 저장소에 올려 둔 사진이 있으면 그것을, 없으면 상품에 적힌 값을 쓴다.
                // 사진이 아예 없는 상품은 placeholder 가 남는다.
                .orElseGet(() -> ProductImagesResponse.ofMasterFallback(
                        masterImageSource.urlFor(product)));
    }

    /**
     * 가장 최근 확정 세션에서 보관소에 실제로 올라간 촬영 이미지. 세션이 없거나 올라간 사진이
     * 하나도 없으면 비어 있는 Optional 이다 — 셋 다 호출자 입장에서는 "촬영본 없음"으로 같다.
     */
    private Optional<List<MeasurementImage>> storedConfirmedImages(Long productId) {
        return sessionRepository
                .findFirstByProductIdAndStatusOrderByConfirmedAtDesc(productId, MeasurementStatus.CONFIRMED)
                .map(MeasurementSession::getImages)
                .map(images -> images.stream().filter(MeasurementImage::isStored).toList())
                .filter(images -> !images.isEmpty());
    }
}
