package com.awesome.backend.inbound.service;

import com.awesome.backend.inbound.entity.Product;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 외부 모델 서버 호출 (D-04, {@code INFERENCE_API_URL}).
 *
 * <p>⚠️ 요청·응답 필드명은 아직 AI팀과 합의 전인 잠정 계약이다. docs/05 §3 이 정한 것은
 * "응답 형식은 02 §1-3 의 inferred·confidence 기준" 까지이며, 확정되면 여기와 02 를 함께 고친다.
 *
 * <p>어떤 실패든 예외로 새지 않고 {@code MEASURE_FAILED} 로 내려보낸다 — 프론트가 HTTP 에러가
 * 아니라 상태값으로 분기하도록 계약돼 있기 때문이다 (02 §1-3).
 */
public class HttpInferenceClient implements InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpInferenceClient.class);

    private final RestClient restClient;

    public HttpInferenceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public InferenceResult infer(Product product) {
        try {
            Response response = restClient.post()
                    .body(new Request(product.id(), product.gtin()))
                    .retrieve()
                    .body(Response.class);

            if (response == null || response.widthCm() == null || response.lengthCm() == null
                    || response.heightCm() == null) {
                log.warn("추론 응답에 치수가 없다. productId={}", product.id());
                return InferenceResult.failed("INVALID_RESPONSE");
            }

            return InferenceResult.success(response.widthCm(), response.lengthCm(),
                    response.heightCm(), response.confidence());

        } catch (Exception e) {
            // 타임아웃·연결 실패·5xx 를 구분하지 않는다 — 프론트 동작(수동 입력 fallback)이 같다.
            log.warn("추론 호출 실패. productId={}", product.id(), e);
            return InferenceResult.failed("TIMEOUT");
        }
    }

    record Request(Long productId, String gtin) {
    }

    record Response(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm, BigDecimal confidence) {
    }
}
