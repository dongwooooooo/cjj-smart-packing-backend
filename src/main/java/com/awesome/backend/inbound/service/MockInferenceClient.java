package com.awesome.backend.inbound.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 모델 서버 없이 쓰는 mock 추론 (D-04).
 *
 * <p>기준 치수를 매 호출 흔들어 준다 — 값이 고정이면 "게이트 미통과 → 재촬영으로 해제"
 * (02 §1-3) 경로가 영원히 풀리지 않아 시연이 막힌다. 같은 이유로 confidence 하한을
 * 게이트 임계값보다 낮게 잡아, 미통과 화면도 가끔 나오게 한다.
 *
 * <p>기준 치수는 데모 정답치({@code demo_product.gt_*})가 있으면 그것을, 없으면 설정값을 쓴다
 * (docs/demo-subsystem-spec.md §6). 사진은 보지 않는다.
 */
public class MockInferenceClient implements InferenceClient {

    private final InferenceProperties.Mock mock;
    private final DemoProductRepository demoProducts;

    public MockInferenceClient(InferenceProperties.Mock mock, DemoProductRepository demoProducts) {
        this.mock = mock;
        this.demoProducts = demoProducts;
    }

    @Override
    public InferenceResult infer(Product product, List<CameraImage> images) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (mock.failureRate() > 0 && random.nextDouble() < mock.failureRate()) {
            return InferenceResult.failed("TIMEOUT");
        }

        BigDecimal confidence = BigDecimal.valueOf(
                        random.nextDouble(mock.minConfidence(), mock.maxConfidence()))
                .setScale(3, RoundingMode.HALF_UP);

        Base base = demoProducts.findById(product.gtin())
                .map(demo -> new Base(demo.gtWidthCm(), demo.gtLengthCm(), demo.gtHeightCm()))
                .orElseGet(() -> new Base(mock.baseWidthCm(), mock.baseLengthCm(), mock.baseHeightCm()));

        return InferenceResult.success(
                jitter(base.widthCm(), random),
                jitter(base.lengthCm(), random),
                jitter(base.heightCm(), random),
                confidence);
    }

    private record Base(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm) {
    }

    /** 기준값에 ±jitterRatio 를 적용한다. 스키마가 소수 1자리(DECIMAL(5,1))라 거기 맞춰 반올림한다. */
    private BigDecimal jitter(BigDecimal base, ThreadLocalRandom random) {
        double factor = 1.0 + random.nextDouble(-mock.jitterRatio(), mock.jitterRatio());
        return base.multiply(BigDecimal.valueOf(factor)).setScale(1, RoundingMode.HALF_UP);
    }
}
