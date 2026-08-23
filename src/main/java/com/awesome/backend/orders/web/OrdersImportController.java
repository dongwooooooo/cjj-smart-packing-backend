package com.awesome.backend.orders.web;

import com.awesome.backend.orders.application.OrderImportResult;
import com.awesome.backend.orders.application.OrderImportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 출고지시 접수 (명세 §2). admin 경로라 화면에서 부르지 않고 Swagger로만 노출된다. */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class OrdersImportController {

    private final OrderImportService orderImportService;

    public OrdersImportController(OrderImportService orderImportService) {
        this.orderImportService = orderImportService;
    }

    @PostMapping("/import")
    public OrdersImportResponse importOrders(@Valid @RequestBody OrdersImportRequest request) {
        OrderImportResult result = orderImportService.importOrders(request.toCommand());
        return OrdersImportResponse.of(request.batchId(), result);
    }
}
