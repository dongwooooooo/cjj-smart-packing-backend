package com.awesome.backend.inbound.service;

import com.awesome.backend.inbound.controller.MeasurementResponse;
import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.entity.MeasurementStatus;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.CategoryAttributeMapRepository;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 촬영 결과를 세션으로 남기는 쓰기 경로 (02 §1-3).
 *
 * <p>{@link MeasurementService} 와 분리한 이유는 트랜잭션 경계다. 추론(Lambda)은 콜드 스타트에
 * 10초까지 걸리므로 트랜잭션 밖에서 부르고, 결과가 정해진 뒤 여기서만 트랜잭션을 연다.
 * 같은 빈 안의 메서드 호출은 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않으므로
 * 별도 빈이어야 한다.
 */
@Component
public class MeasurementWriter {

    /** 촬영함 고정 카메라 대수. measurement_image 의 camera_no CHECK 제약과 같은 값이다. */
    private static final List<MeasurementStatus> OPEN_STATUSES =
            List.of(MeasurementStatus.INFERRED, MeasurementStatus.MEASURE_FAILED);

    private static final MeasurementResponse.HandlingDefaults NO_HANDLING_DEFAULTS =
            new MeasurementResponse.HandlingDefaults(false, false, false);

    private final ProductRepository productRepository;
    private final MeasurementSessionRepository sessionRepository;
    private final CategoryAttributeMapRepository categoryAttributeMapRepository;
    private final MeasurementGate gate;

    public MeasurementWriter(ProductRepository productRepository,
                             MeasurementSessionRepository sessionRepository,
                             CategoryAttributeMapRepository categoryAttributeMapRepository,
                             MeasurementGate gate) {
        this.productRepository = productRepository;
        this.sessionRepository = sessionRepository;
        this.categoryAttributeMapRepository = categoryAttributeMapRepository;
        this.gate = gate;
    }

    /** 재촬영이면 이전 미확정 세션을 정리하고, 결과 상태의 세션을 하나 만든다. */
    @Transactional
    public MeasurementResponse save(Long productId, BigDecimal measuredWeightKg,
                                    List<CameraImage> images, InferenceResult result) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));

        discardOpenSessions(productId);

        if (result.failed()) {
            MeasurementSession session = sessionRepository.save(
                    MeasurementSession.failed(product, measuredWeightKg));
            return MeasurementResponse.failed(session, result.failReason());
        }

        // 축 규약(D-18): 높이는 그대로 두고 가로·세로만 긴 쪽이 width 가 되도록 정렬한다.
        boolean swap = result.widthCm().compareTo(result.lengthCm()) < 0;
        BigDecimal widthCm = swap ? result.lengthCm() : result.widthCm();
        BigDecimal lengthCm = swap ? result.widthCm() : result.lengthCm();
        BigDecimal heightCm = result.heightCm();

        List<String> gateFailReasons = gate.evaluate(widthCm, lengthCm, heightCm, result.confidence());

        MeasurementSession session = sessionRepository.save(MeasurementSession.inferred(
                product, widthCm, lengthCm, heightCm, measuredWeightKg,
                result.confidence(), gateFailReasons.isEmpty(), gateFailReasons));

        attachImages(session, images);

        return MeasurementResponse.inferred(session, handlingDefaults(product));
    }

    /**
     * 카메라 3대분 이미지 경로를 붙인다. 1-6 제품 이미지 조회가 이 경로를 그대로 돌려준다.
     *
     * <p>추론에 쓴 사진이 있으면 그 조회 URL 을 기록한다. 없으면(mock 에 데모 이미지 없는 상품)
     * 실제 파일 없이 경로 문자열만 남긴다 — 실물 촬영이 붙으면 저장 위치만 바뀐다.
     */
    private void attachImages(MeasurementSession session, List<CameraImage> images) {
        if (!images.isEmpty()) {
            images.forEach(image -> session.addImage(image.cameraNo(), image.url()));
            return;
        }
        for (short cameraNo = 1; cameraNo <= MeasurementImageSource.CAMERA_COUNT; cameraNo++) {
            session.addImage(cameraNo, "/files/m/%d-%d.jpg".formatted(session.getId(), cameraNo));
        }
    }

    /** 분류별 취급속성 기본값. 행이 없는 분류는 전부 false 로 취급한다 (03 §2). */
    private MeasurementResponse.HandlingDefaults handlingDefaults(Product product) {
        return categoryAttributeMapRepository.findByMediumCategoryCode(product.mediumCategoryCode())
                .map(map -> new MeasurementResponse.HandlingDefaults(
                        map.isDefaultRefrigerate(), map.isDefaultFragile(), map.isDefaultIrregular()))
                .orElse(NO_HANDLING_DEFAULTS);
    }

    /** 재촬영: 이전 세션은 폐기한다. 확정된 세션은 건드리지 않는다. */
    private void discardOpenSessions(Long productId) {
        sessionRepository.findByProductIdAndStatusIn(productId, OPEN_STATUSES)
                .forEach(MeasurementSession::discard);
    }
}
