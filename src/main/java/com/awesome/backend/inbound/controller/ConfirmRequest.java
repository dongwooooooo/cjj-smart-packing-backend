package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.entity.ConfirmMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 측정 확정 요청 (02 §1-4).
 *
 * <p>필드별 필수 여부가 {@code method} 에 따라 갈려서 애노테이션만으로는 다 걸러지지 않는다 —
 * MANUAL 의 {@code dims}, 그리고 세션에 저울값이 없을 때의 {@code weightKg} 는 서비스에서 검증한다.
 *
 * @param weightKg 생략/null 이면 세션의 저울값을 쓴다. 값을 주면 수기 수정이다.
 */
public record ConfirmRequest(
        @NotNull(message = "method 는 필수입니다. APPROVE 또는 MANUAL 이어야 합니다.")
        ConfirmMethod method,

        /** MANUAL 에서만 쓴다. APPROVE 는 세션의 추론값을 그대로 확정한다. */
        @Valid Dimensions dims,

        @DecimalMin(value = "0.0", inclusive = false, message = "무게는 0보다 커야 합니다.")
        @Digits(integer = 4, fraction = 3, message = "무게는 소수 3자리까지입니다.")
        BigDecimal weightKg,

        @NotNull(message = "handling 은 필수입니다.")
        @Valid Handling handling) {

    public record Dimensions(
            @NotNull(message = "widthCm 은 필수입니다.")
            @DecimalMin(value = "0.0", inclusive = false, message = "치수는 0보다 커야 합니다.")
            @Digits(integer = 4, fraction = 1, message = "치수는 소수 1자리까지입니다.")
            BigDecimal widthCm,

            @NotNull(message = "lengthCm 은 필수입니다.")
            @DecimalMin(value = "0.0", inclusive = false, message = "치수는 0보다 커야 합니다.")
            @Digits(integer = 4, fraction = 1, message = "치수는 소수 1자리까지입니다.")
            BigDecimal lengthCm,

            @NotNull(message = "heightCm 은 필수입니다.")
            @DecimalMin(value = "0.0", inclusive = false, message = "치수는 0보다 커야 합니다.")
            @Digits(integer = 4, fraction = 1, message = "치수는 소수 1자리까지입니다.")
            BigDecimal heightCm) {
    }

    /** 확정 취급속성. 1-3 의 handlingDefaults 를 프론트가 채워 되돌려준다. */
    public record Handling(
            @NotNull(message = "refrigerate 는 필수입니다.") Boolean refrigerate,
            @NotNull(message = "fragile 은 필수입니다.") Boolean fragile,
            @NotNull(message = "irregular 는 필수입니다.") Boolean irregular) {
    }
}
