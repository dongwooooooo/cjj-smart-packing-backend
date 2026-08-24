package com.awesome.backend.inbound.scan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 바코드 스캔 요청 (1-1). */
public record ScanRequest(
        @NotBlank(message = "바코드는 필수입니다.")
        @Pattern(regexp = "[0-9]{13}", message = "바코드는 숫자 13자리(GTIN-13)여야 합니다.")
        String barcode) {
}
