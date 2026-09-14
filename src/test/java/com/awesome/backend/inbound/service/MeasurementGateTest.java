package com.awesome.backend.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 신뢰도 게이트 (02 §1-3). 임계값 경계와 사유 조합만 본다 —
 * mock 추론이 confidence 를 난수로 뽑으므로, 게이트 판정은 여기서 결정적으로 검증한다.
 */
class MeasurementGateTest {

    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.85");
    private static final BigDecimal MAX_ASPECT_RATIO = new BigDecimal("8.0");
    private static final BigDecimal MAX_DIMENSION_CM = new BigDecimal("100.0");

    private final MeasurementGate gate = new MeasurementGate(new InferenceProperties(
            null, null, null, 8, null,
            new InferenceProperties.Gate(MIN_CONFIDENCE, MAX_ASPECT_RATIO, MAX_DIMENSION_CM),
            null));

    private static BigDecimal cm(String value) {
        return new BigDecimal(value);
    }

    @Test
    void 정상_치수와_충분한_신뢰도는_통과한다() {
        assertThat(gate.evaluate(cm("12.0"), cm("9.0"), cm("20.0"), cm("0.93"))).isEmpty();
    }

    @Test
    void 임계값과_같은_신뢰도는_통과한다() {
        assertThat(gate.evaluate(cm("12.0"), cm("9.0"), cm("20.0"), MIN_CONFIDENCE)).isEmpty();
    }

    @Test
    void 임계값_미만이면_LOW_CONFIDENCE() {
        assertThat(gate.evaluate(cm("12.0"), cm("9.0"), cm("20.0"), cm("0.84")))
                .containsExactly("LOW_CONFIDENCE");
    }

    @Test
    void 신뢰도가_없으면_신뢰도_판정만_건너뛴다() {
        // 현재 Lambda 모델은 confidence 를 주지 않는다 (D-24). 나머지 게이트는 그대로다.
        assertThat(gate.evaluate(cm("12.0"), cm("9.0"), cm("20.0"), null)).isEmpty();
        assertThat(gate.evaluate(cm("120.0"), cm("9.0"), cm("20.0"), null))
                .containsExactlyInAnyOrder("ASPECT_RATIO", "OUT_OF_RANGE");
    }

    @Test
    void 너무_납작하면_ASPECT_RATIO() {
        // 90 / 10 = 9.0 > 8.0
        assertThat(gate.evaluate(cm("90.0"), cm("10.0"), cm("20.0"), cm("0.95")))
                .containsExactly("ASPECT_RATIO");
    }

    @Test
    void 상한을_넘으면_OUT_OF_RANGE() {
        assertThat(gate.evaluate(cm("120.0"), cm("100.0"), cm("90.0"), cm("0.95")))
                .containsExactly("OUT_OF_RANGE");
    }

    @Test
    void 사유는_동시에_여러개_잡힌다() {
        assertThat(gate.evaluate(cm("120.0"), cm("5.0"), cm("10.0"), cm("0.10")))
                .containsExactlyInAnyOrder("LOW_CONFIDENCE", "ASPECT_RATIO", "OUT_OF_RANGE");
    }

    @Test
    void 치수가_0이면_나눗셈_대신_ASPECT_RATIO로_잡는다() {
        assertThat(gate.evaluate(cm("12.0"), cm("0.0"), cm("20.0"), cm("0.95")))
                .containsExactly("ASPECT_RATIO");
    }
}
