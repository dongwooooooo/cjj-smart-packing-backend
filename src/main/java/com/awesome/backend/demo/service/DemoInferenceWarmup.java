package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inbound.service.CameraImage;
import com.awesome.backend.inbound.service.InferenceClient;
import com.awesome.backend.inbound.service.InferenceResult;
import com.awesome.backend.inbound.service.MeasurementImageSource;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 시연 전에 추론을 한 번 깨운다.
 *
 * <p>Lambda 는 첫 호출이 콜드 스타트라 10초까지 걸린다 — 촬영 계약의 타임아웃이 8초라
 * 시연 첫 장면이 그대로 실패로 보인다. 리셋 직후 한 번 불러 웜 상태로 만들어 둔다.
 *
 * <p>트랜잭션 밖에서 부른다. 리셋 트랜잭션 안에서 부르면 그 10초 동안 DB 커넥션을 잡는다.
 * 워밍은 부가 효과라 실패해도 리셋은 성공으로 둔다 — 결과는 응답 요약에만 남긴다.
 */
@Component
public class DemoInferenceWarmup {

    private static final Logger log = LoggerFactory.getLogger(DemoInferenceWarmup.class);

    private final DemoDataProperties properties;
    private final DemoProductRepository demoProducts;
    private final ProductRepository productRepository;
    private final MeasurementImageSource imageSource;
    private final InferenceClient inferenceClient;

    public DemoInferenceWarmup(DemoDataProperties properties, DemoProductRepository demoProducts,
                               ProductRepository productRepository,
                               MeasurementImageSource imageSource, InferenceClient inferenceClient) {
        this.properties = properties;
        this.demoProducts = demoProducts;
        this.productRepository = productRepository;
        this.imageSource = imageSource;
        this.inferenceClient = inferenceClient;
    }

    /** 요약에 실을 한 줄. 끄면 빈 값. */
    public Optional<String> warmUp() {
        if (!properties.warmupInference()) {
            return Optional.empty();
        }
        Optional<Product> target = firstInboundProduct();
        if (target.isEmpty()) {
            return Optional.of("추론 워밍: 건너뜀(입고 풀 상품 없음)");
        }

        long startedAt = System.nanoTime();
        try {
            List<CameraImage> images = imageSource.load(target.get());
            InferenceResult result = inferenceClient.infer(target.get(), images);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            if (result.failed()) {
                log.warn("추론 워밍 실패 — {} ({}ms)", result.failReason(), elapsedMs);
                return Optional.of("추론 워밍: 실패(%s, %dms)".formatted(result.failReason(), elapsedMs));
            }
            log.info("추론 워밍 완료 — {}ms", elapsedMs);
            return Optional.of("추론 워밍: 성공(%dms)".formatted(elapsedMs));
        } catch (RuntimeException e) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("추론 워밍 실패 — {} ({}ms)", e.getMessage(), elapsedMs);
            return Optional.of("추론 워밍: 실패(%s)".formatted(e.getMessage()));
        }
    }

    private Optional<Product> firstInboundProduct() {
        return demoProducts.findByPool(DemoProduct.Pool.INBOUND).stream()
                .findFirst()
                .flatMap(demo -> productRepository.findByGtin(demo.gtin()));
    }
}
