package com.awesome.backend.demo.service;

/** 런 시작 결과 요약 (명세 §4-5). */
public record DemoRunSummary(String runId, Products products, int queuedBatches,
                             Totes totes, BoxTypes boxTypes) {

    public record Products(int inbound, int outbound) {
    }

    public record Totes(int idle, int assigned) {
    }

    public record BoxTypes(int count, int restoredTo) {
    }
}
