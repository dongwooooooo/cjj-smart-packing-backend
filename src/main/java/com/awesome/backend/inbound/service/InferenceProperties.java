package com.awesome.backend.inbound.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 추론 연동 설정 (application.yml 의 {@code inference.*}).
 *
 * <p>게이트 임계값은 명세에 확정 숫자가 없어 잠정값이다 (02 §1-3 은 "임계값 미만·종횡비
 * 이상·상한 초과" 라고만 정한다). 모델 성능을 보고 조정할 값이라 코드가 아닌 설정에 둔다.
 *
 * @param lambdaFunction 호출할 Lambda 함수. 별칭까지 붙인 이름({@code name:alias})을 권장한다 —
 *                       provisioned concurrency 가 별칭에 걸려 있기 때문이다 (D-24). 비우면 mock
 * @param apiKey         함수가 검사하는 {@code X-API-Key}. IAM 호출 권한과 별개다
 * @param region         함수가 있는 리전
 * @param timeoutSeconds 추론 응답 상한. 02 §1-3 의 8초 계약이다
 */
@ConfigurationProperties(prefix = "inference")
public record InferenceProperties(String lambdaFunction, String apiKey, String region,
                                  int timeoutSeconds, Gate gate, Mock mock) {

    /** Lambda 함수가 지정되지 않았으면 mock 으로 동작한다 (D-04). */
    public boolean useMock() {
        return lambdaFunction == null || lambdaFunction.isBlank();
    }

    public record Gate(BigDecimal minConfidence, BigDecimal maxAspectRatio, BigDecimal maxDimensionCm) {
    }

    public record Mock(BigDecimal baseWidthCm, BigDecimal baseLengthCm, BigDecimal baseHeightCm,
                       double jitterRatio, double minConfidence, double maxConfidence,
                       double failureRate) {
    }
}
