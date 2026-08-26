package com.awesome.backend.demo.service;

/**
 * 리셋 결과 (명세 §4-5). 구조로도 주고 사람이 읽을 한 덩어리로도 준다 —
 * 시연 중에는 Swagger 화면에서 눈으로 확인하는 게 전부다.
 */
public record DemoResetSummary(Products products, int queuedBatches, Totes totes,
                               BoxTypes boxTypes, String summary) {

    public record Products(int inbound, int outbound) {
    }

    public record Totes(int idle, int assigned) {
    }

    public record BoxTypes(int count, int stockQty) {
    }

    /** 워밍 결과처럼 리셋 트랜잭션 밖에서 정해지는 항목을 요약 끝에 덧붙인다. */
    public DemoResetSummary withExtraSummaryLine(String line) {
        return new DemoResetSummary(products(), queuedBatches(), totes(), boxTypes(),
                summary() + "\n" + line);
    }
}
