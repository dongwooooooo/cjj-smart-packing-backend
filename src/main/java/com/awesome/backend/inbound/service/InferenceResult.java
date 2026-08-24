package com.awesome.backend.inbound.service;

import java.math.BigDecimal;

/**
 * 추론 API 한 번의 결과. 실패는 예외가 아니라 값으로 표현한다 —
 * 타임아웃·모델 오류는 HTTP 에러가 아닌 {@code MEASURE_FAILED} 상태로 200 응답되기 때문이다 (02 §1-3).
 *
 * @param failReason 실패 사유(TIMEOUT 등). 성공이면 null.
 */
public record InferenceResult(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm,
                              BigDecimal confidence, String failReason) {

    public static InferenceResult success(BigDecimal widthCm, BigDecimal lengthCm,
                                          BigDecimal heightCm, BigDecimal confidence) {
        return new InferenceResult(widthCm, lengthCm, heightCm, confidence, null);
    }

    public static InferenceResult failed(String failReason) {
        return new InferenceResult(null, null, null, null, failReason);
    }

    public boolean failed() {
        return failReason != null;
    }
}
