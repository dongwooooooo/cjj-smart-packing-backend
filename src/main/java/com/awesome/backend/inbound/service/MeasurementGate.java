package com.awesome.backend.inbound.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 신뢰도 게이트 (02 §1-3). 미통과면 프론트가 승인 버튼을 비활성화하고 사유를 띄우며,
 * 해제는 재촬영 또는 수기 확정(1-4 MANUAL)뿐이다.
 *
 * <p>게이트는 치수 추론에만 적용된다 — 무게는 저울 실측값이라 대상이 아니다 (D-02).
 */
@Component
public class MeasurementGate {

    private final InferenceProperties.Gate gate;

    public MeasurementGate(InferenceProperties properties) {
        this.gate = properties.gate();
    }

    /** 통과면 빈 리스트. 사유는 여러 개가 동시에 잡힐 수 있어 전부 모아 돌려준다. */
    public List<String> evaluate(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm,
                                 BigDecimal confidence) {
        List<String> reasons = new ArrayList<>();

        if (confidence == null || confidence.compareTo(gate.minConfidence()) < 0) {
            reasons.add("LOW_CONFIDENCE");
        }

        BigDecimal longest = widthCm.max(lengthCm).max(heightCm);
        BigDecimal shortest = widthCm.min(lengthCm).min(heightCm);

        if (shortest.signum() <= 0
                || longest.divide(shortest, 3, java.math.RoundingMode.HALF_UP)
                        .compareTo(gate.maxAspectRatio()) > 0) {
            reasons.add("ASPECT_RATIO");
        }

        if (longest.compareTo(gate.maxDimensionCm()) > 0) {
            reasons.add("OUT_OF_RANGE");
        }

        return reasons;
    }
}
