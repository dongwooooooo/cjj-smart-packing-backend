package com.awesome.backend.orders.application;

import java.util.List;
import java.util.Map;

/**
 * 접수 결과 (명세 §2 응답). orders·shipments·splitOrders는 저장에 성공한 것만 센다.
 * U1 단계에서는 저장이 없어 전부 0이다.
 */
public record OrderImportResult(int orders, int shipments, int splitOrders,
                                List<Rejection> rejected) {

    /** 2층 거부 (명세 §3). U1 범위 밖이라 아직 채워지지 않는다. */
    public record Rejection(String receiptNo, String reason, Map<String, Object> detail) {
    }

    public static OrderImportResult empty() {
        return new OrderImportResult(0, 0, 0, List.of());
    }
}
