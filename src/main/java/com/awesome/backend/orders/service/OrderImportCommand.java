package com.awesome.backend.orders.service;

import java.time.LocalDateTime;
import java.util.List;

/** 접수 서비스의 입력 모델. 요청 DTO와 분리해 컨트롤러 계층 변경이 서비스로 새지 않게 한다. */
public record OrderImportCommand(String batchId, List<OrderLine> orders) {

    public record OrderLine(String receiptNo, String regionCode, LocalDateTime orderedAt,
                            List<ItemLine> items) {
    }

    public record ItemLine(String gtin, int qty) {
    }
}
