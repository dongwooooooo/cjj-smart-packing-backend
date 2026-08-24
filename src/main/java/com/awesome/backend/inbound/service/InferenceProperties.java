package com.awesome.backend.inbound.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 추론 연동 설정 (application.yml 의 {@code inference.*}).
 *
 * <p>게이트 임계값은 명세에 확정 숫자가 없어 잠정값이다 (02 §1-3 은 "임계값 미만·종횡비
 * 이상·상한 초과" 라고만 정한다). 모델 성능을 보고 조정할 값이라 코드가 아닌 설정에 둔다.
 */
@ConfigurationProperties(prefix = "inference")
public record InferenceProperties(String apiUrl, int timeoutSeconds, Gate gate, Mock mock) {

    /** 외부 모델 서버 주소가 비어 있으면 mock 으로 동작한다 (D-04). */
    public boolean useMock() {
        return apiUrl == null || apiUrl.isBlank();
    }

    public record Gate(BigDecimal minConfidence, BigDecimal maxAspectRatio, BigDecimal maxDimensionCm) {
    }

    public record Mock(BigDecimal baseWidthCm, BigDecimal baseLengthCm, BigDecimal baseHeightCm,
                       double jitterRatio, double minConfidence, double maxConfidence,
                       double failureRate) {
    }
}
