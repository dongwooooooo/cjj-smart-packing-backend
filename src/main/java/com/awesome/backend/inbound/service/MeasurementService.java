package com.awesome.backend.inbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.controller.MeasurementResponse;
import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.entity.MeasurementStatus;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.CategoryAttributeMapRepository;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 촬영·추론 세션 (02 §1-3).
 *
 * <p>추론이 동기 응답이라 세션은 항상 결과 상태(INFERRED 또는 MEASURE_FAILED)로 생성된다.
 * 실패는 예외가 아니라 상태값으로 내려간다 — 프론트가 수동 입력 fallback 을 여는 분기다.
 */
@Service
public class MeasurementService {

    /** 촬영함 고정 카메라 대수. measurement_image 의 camera_no CHECK 제약과 같은 값이다. */
    private static final short CAMERA_COUNT = 3;

    /** 재촬영 시 정리 대상 — 아직 확정도 폐기도 안 된 세션. */
    private static final List<MeasurementStatus> OPEN_STATUSES =
            List.of(MeasurementStatus.INFERRED, MeasurementStatus.MEASURE_FAILED);

    private static final MeasurementResponse.HandlingDefaults NO_HANDLING_DEFAULTS =
            new MeasurementResponse.HandlingDefaults(false, false, false);

    private final ProductRepository productRepository;
    private final MeasurementSessionRepository sessionRepository;
    private final CategoryAttributeMapRepository categoryAttributeMapRepository;
    private final InferenceClient inferenceClient;
    private final MeasurementGate gate;

    public MeasurementService(ProductRepository productRepository,
                              MeasurementSessionRepository sessionRepository,
                              CategoryAttributeMapRepository categoryAttributeMapRepository,
                              InferenceClient inferenceClient,
                              MeasurementGate gate) {
        this.productRepository = productRepository;
        this.sessionRepository = sessionRepository;
        this.categoryAttributeMapRepository = categoryAttributeMapRepository;
        this.inferenceClient = inferenceClient;
        this.gate = gate;
    }

    /**
     * 촬영 1회. 같은 productId 로 다시 부르면 재촬영이며, 이전 미확정 세션은 DISCARDED 로 정리한다.
     */
    @Transactional
    public MeasurementResponse measure(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));

        discardOpenSessions(productId);

        // 시연에서는 저울 하드웨어 대신 사전 등록 무게를 조회해 쓴다 (D-10).
        // 추론과 별개 경로라 추론이 실패해도 이 값은 응답에 실린다.
        BigDecimal measuredWeightKg = product.weightKg();

        InferenceResult result = inferenceClient.infer(product);
        if (result.failed()) {
            MeasurementSession session = sessionRepository.save(
                    MeasurementSession.failed(product, measuredWeightKg));
            return MeasurementResponse.failed(session, result.failReason());
        }

        // 축 규약(D-15): 높이는 그대로 두고 가로·세로만 긴 쪽이 width 가 되도록 정렬한다.
        // 모델 출력이 규약을 벗어나도 저장 전 여기서 맞춘다 (docs/05 §3 추론 응답 계약).
        boolean swap = result.widthCm().compareTo(result.lengthCm()) < 0;
        BigDecimal widthCm = swap ? result.lengthCm() : result.widthCm();
        BigDecimal lengthCm = swap ? result.widthCm() : result.lengthCm();
        BigDecimal heightCm = result.heightCm();

        List<String> gateFailReasons = gate.evaluate(widthCm, lengthCm, heightCm, result.confidence());

        MeasurementSession session = sessionRepository.save(MeasurementSession.inferred(
                product, widthCm, lengthCm, heightCm, measuredWeightKg,
                result.confidence(), gateFailReasons.isEmpty(), gateFailReasons));

        attachImages(session);

        return MeasurementResponse.inferred(session, handlingDefaults(product));
    }

    /** 재촬영: 이전 세션은 폐기한다. 확정된 세션은 건드리지 않는다. */
    private void discardOpenSessions(Long productId) {
        sessionRepository.findByProductIdAndStatusIn(productId, OPEN_STATUSES)
                .forEach(MeasurementSession::discard);
    }

    /**
     * 카메라 3대분 이미지 경로를 붙인다.
     *
     * <p>시연에는 카메라가 없어 실제 파일을 만들지 않고 경로 문자열만 기록한다 —
     * 1-6 제품 이미지 조회가 이 경로를 그대로 돌려주고, 실물 촬영이 붙으면 저장 위치만 바뀐다.
     */
    private void attachImages(MeasurementSession session) {
        for (short cameraNo = 1; cameraNo <= CAMERA_COUNT; cameraNo++) {
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
}
