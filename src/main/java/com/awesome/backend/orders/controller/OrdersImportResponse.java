package com.awesome.backend.orders.controller;

import com.awesome.backend.orders.service.OrderImportResult;
import java.util.List;
import java.util.Map;

/** 접수 응답 (명세 §2). batchId를 그대로 되돌려주고 나머지는 결과 요약이다. */
public record OrdersImportResponse(String batchId, int orders, int shipments, int splitOrders,
                                   List<RejectedOrder> rejected) {

    public record RejectedOrder(String receiptNo, String reason, Map<String, Object> detail) {
    }

    public static OrdersImportResponse of(String batchId, OrderImportResult result) {
        return new OrdersImportResponse(batchId, result.orders(), result.shipments(),
                result.splitOrders(),
                result.rejected().stream()
                        .map(r -> new RejectedOrder(r.receiptNo(), r.reason(), r.detail()))
                        .toList());
    }
}
