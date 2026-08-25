package com.awesome.backend.demo.service;

import com.awesome.backend.orders.service.OrderImportResult;
import java.util.List;
import java.util.Map;

/**
 * 배치 한 건 투입 결과 (명세 §5). 접수 응답에 대기열 정보를 얹었다 —
 * 방금 무엇이 나갔고 몇 개 남았는지가 한 화면에 보여야 한다.
 */
public record DemoNextResult(int seq, int remaining, String batchId, int orders, int shipments,
                             int splitOrders, List<Rejected> rejected) {

    public record Rejected(String receiptNo, String reason, Map<String, Object> detail) {
    }

    public static DemoNextResult of(int seq, int remaining, String batchId,
                                    OrderImportResult result) {
        return new DemoNextResult(seq, remaining, batchId, result.orders(), result.shipments(),
                result.splitOrders(),
                result.rejected().stream()
                        .map(r -> new Rejected(r.receiptNo(), r.reason(), r.detail()))
                        .toList());
    }
}
