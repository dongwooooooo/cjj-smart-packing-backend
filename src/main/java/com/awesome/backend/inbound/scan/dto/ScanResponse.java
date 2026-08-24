package com.awesome.backend.inbound.scan.dto;

import com.awesome.backend.inbound.scan.ScanJudgment;

/** 스캔 응답 (1-1). 세 케이스가 같은 형태를 쓰며, UNKNOWN 이면 product 가 null 이다. */
public record ScanResponse(String judgment, ProductSummary product) {

    public static ScanResponse of(ScanJudgment judgment, ProductSummary product) {
        return new ScanResponse(judgment.name(), product);
    }

    public static ScanResponse unknown() {
        return new ScanResponse(ScanJudgment.UNKNOWN.name(), null);
    }
}
