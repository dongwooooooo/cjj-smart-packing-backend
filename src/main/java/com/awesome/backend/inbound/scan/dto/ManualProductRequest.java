package com.awesome.backend.inbound.scan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 미등록 바코드의 임시 마스터 생성 요청 (1-2).
 * 분류는 이름이 아니라 코드로 받는다 (D-13) — 드롭다운은 1-7 로 채운다.
 */
public record ManualProductRequest(
        @NotBlank(message = "바코드는 필수입니다.")
        @Pattern(regexp = "[0-9]{13}", message = "바코드는 숫자 13자리(GTIN-13)여야 합니다.")
        String gtin,

        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 200, message = "상품명은 200자를 넘을 수 없습니다.")
        String name,

        @NotBlank(message = "중분류 코드는 필수입니다.")
        String mediumCategoryCode) {
}
