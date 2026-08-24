package com.awesome.backend.orders.controller;

import com.awesome.backend.orders.service.OrderImportCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 출고지시 접수 요청 (명세 §2). 요청 형식은 컨트롤러 계층에만 두고,
 * 서비스는 {@link OrderImportCommand}만 본다 — 요청 형식이 바뀌어도 서비스가 흔들리지 않는다.
 *
 * <p>여기 붙은 제약이 명세 §3 1층 중 형식 검증분이다. 위반은 배치 전체 400.
 */
public record OrdersImportRequest(
        @NotBlank String batchId,
        @NotEmpty @Valid List<OrderRequest> orders) {

    public record OrderRequest(
            @NotBlank String receiptNo,
            @NotBlank String regionCode,
            @NotNull LocalDateTime orderedAt,
            @NotEmpty @Valid List<ItemRequest> items) {
    }

    public record ItemRequest(
            @NotBlank String gtin,
            @Positive int qty) {
    }

    public OrderImportCommand toCommand() {
        return new OrderImportCommand(batchId, orders.stream()
                .map(o -> new OrderImportCommand.OrderLine(o.receiptNo(), o.regionCode(), o.orderedAt(),
                        o.items().stream()
                                .map(i -> new OrderImportCommand.ItemLine(i.gtin(), i.qty()))
                                .toList()))
                .toList());
    }
}
