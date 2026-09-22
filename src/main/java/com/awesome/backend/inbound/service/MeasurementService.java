package com.awesome.backend.inbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.controller.ConfirmRequest;
import com.awesome.backend.inbound.controller.ConfirmResponse;
import com.awesome.backend.inbound.controller.MeasurementResponse;
import com.awesome.backend.inbound.entity.ConfirmMethod;
import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.entity.MeasurementStatus;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.CategoryAttributeMapRepository;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.common.metrics.StageTimers;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(MeasurementService.class);



    private final ProductRepository productRepository;
    private final MeasurementSessionRepository sessionRepository;
    private final MeasurementImageSource imageSource;
    private final InferenceClient inferenceClient;
    private final MeasurementGate gate;
    private final MeasurementWriter writer;

    private final MeterRegistry registry;

    public MeasurementService(ProductRepository productRepository,
                              MeasurementSessionRepository sessionRepository,
                              MeasurementImageSource imageSource,
                              InferenceClient inferenceClient,
                              MeasurementGate gate, MeasurementWriter writer, MeterRegistry registry) {
        this.productRepository = productRepository;
        this.registry = registry;
        this.sessionRepository = sessionRepository;
        this.imageSource = imageSource;
        this.inferenceClient = inferenceClient;
        this.gate = gate;
        this.writer = writer;
    }

    /**
     * 촬영 1회. 같은 productId 로 다시 부르면 재촬영이며, 이전 미확정 세션은 DISCARDED 로 정리한다.
     *
     * <p>추론 호출은 트랜잭션 밖에서 한다. Lambda 는 콜드 스타트에 10초까지 걸려서, 트랜잭션
     * 안에서 부르면 그동안 DB 커넥션을 잡고 있게 된다. 세션은 결과가 정해진 뒤에 만들어지므로
     * (INFERRED / MEASURE_FAILED 둘 중 하나) 순서를 이렇게 두어도 중간 상태가 생기지 않는다.
     */
    public MeasurementResponse measure(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));

        // 시연에서는 저울 하드웨어 대신 사전 등록 무게를 조회해 쓴다 (D-10).
        // 추론과 별개 경로라 추론이 실패해도 이 값은 응답에 실린다.
        BigDecimal measuredWeightKg = product.weightKg();

        // 시연은 카메라 대신 데모 데이터셋 사진을 쓴다. 사진이 없으면 Lambda 추론은 실패하고
        // mock 은 사진을 보지 않는다 — 어느 쪽이든 판단은 클라이언트가 한다.
        long t0 = System.nanoTime();
        List<CameraImage> images = imageSource.load(product);
        long t1 = System.nanoTime();
        InferenceResult result = inferenceClient.infer(product, images);
        long t2 = System.nanoTime();

        MeasurementResponse response = writer.save(productId, measuredWeightKg, images, result);
        long t3 = System.nanoTime();
        // 측정용 구간 로그: 사진 읽기 / 추론 호출 전체 / 세션·사진 저장
        log.info("measure.timing productId={} images={} status={} loadMs={} inferMs={} saveMs={} totalMs={}",
                productId, images.size(), response.status(), (t1 - t0) / 1_000_000,
                (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t3 - t0) / 1_000_000);
        String outcome = response.status();
        StageTimers.record(registry, "measure.stage", "load", outcome, t1 - t0);
        StageTimers.record(registry, "measure.stage", "infer", outcome, t2 - t1);
        StageTimers.record(registry, "measure.stage", "save", outcome, t3 - t2);
        StageTimers.record(registry, "measure.stage", "total", outcome, t3 - t0);
        return response;
    }

    /**
     * 측정 확정 (1-4). 승인(APPROVE)은 세션의 추론값을, 수기(MANUAL)는 요청 치수를 확정한다.
     *
     * <p>재고는 변동하지 않는다 — 재고 증가는 1-5 stock-in 에서만 발생한다 (D-09).
     */
    @Transactional
    public ConfirmResponse confirm(Long sessionId, ConfirmRequest request) {
        MeasurementSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND,
                        "측정 세션을 찾을 수 없습니다.", Map.of("sessionId", sessionId)));

        // 확정·폐기된 세션은 다시 확정하지 않는다. 폐기(DISCARDED)도 같은 코드로 막는다 —
        // 재촬영으로 밀려난 세션을 뒤늦게 확정하면 최신 촬영 결과를 덮어쓴다.
        if (!session.isOpen()) {
            throw new ApiException(ErrorCode.SESSION_ALREADY_CONFIRMED,
                    "이미 확정되었거나 폐기된 세션입니다.",
                    Map.of("sessionId", sessionId, "status", session.getStatus().name()));
        }

        BigDecimal weightKg = resolveWeight(session, request);
        Dims dims = resolveDims(session, request);

        // 축 규약(D-18) 정렬은 세션 확정 안에서 한 번만 한다. product 는 그 결과를 받아 쓴다.
        ConfirmMethod method = request.method();
        session.confirm(method, dims.widthCm(), dims.lengthCm(), dims.heightCm(), weightKg);

        ConfirmRequest.Handling handling = request.handling();
        Product product = session.getProduct();
        product.confirmMeasurement(
                session.getConfirmedWidthCm(), session.getConfirmedLengthCm(),
                session.getConfirmedHeightCm(), session.getConfirmedWeightKg(),
                dimMethodOf(method),
                handling.refrigerate(), handling.fragile(), handling.irregular());

        return ConfirmResponse.from(product);
    }

    /** 요청 weightKg 가 세션 저울값보다 우선한다. 둘 다 없으면 무게 미확정이라 확정할 수 없다. */
    private BigDecimal resolveWeight(MeasurementSession session, ConfirmRequest request) {
        BigDecimal weightKg = request.weightKg() != null
                ? request.weightKg()
                : session.getMeasuredWeightKg();

        if (weightKg == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "세션에 저울값이 없어 weightKg 가 필요합니다.",
                    Map.of("sessionId", session.getId()));
        }
        return weightKg;
    }

    /**
     * APPROVE 는 세션의 추론값을, MANUAL 은 요청 치수를 쓴다.
     * 게이트는 치수 추론에만 적용되므로 MANUAL 에는 걸지 않는다 — MEASURE_FAILED 세션도 수기 확정은 된다.
     */
    private Dims resolveDims(MeasurementSession session, ConfirmRequest request) {
        if (request.method() == ConfirmMethod.MANUAL) {
            ConfirmRequest.Dimensions dims = request.dims();
            if (dims == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "수기 확정에는 dims 가 필요합니다.", Map.of("method", "MANUAL"));
            }
            return new Dims(dims.widthCm(), dims.lengthCm(), dims.heightCm());
        }

        if (!session.isGatePassed()) {
            throw new ApiException(ErrorCode.GATE_NOT_PASSED,
                    "신뢰도 게이트 미통과 세션은 승인할 수 없습니다.",
                    Map.of("reasons", session.getGateFailReasons()));
        }

        return new Dims(session.getInferredWidthCm(), session.getInferredLengthCm(),
                session.getInferredHeightCm());
    }

    /** 1-4 의 method 와 product.dim_method 는 이름이 다르다 — APPROVE 는 추론값 승인이라 INFERRED 다. */
    private String dimMethodOf(ConfirmMethod method) {
        return method == ConfirmMethod.MANUAL ? Product.DIM_METHOD_MANUAL : Product.DIM_METHOD_INFERRED;
    }

    private record Dims(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm) {
    }



}
