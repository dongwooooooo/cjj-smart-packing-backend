package com.awesome.backend.orders.controller;

import com.awesome.backend.orders.service.LineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포장 라인 목록 API.
 *
 * <p>라인별 배송단위 목록({@code /lines/{lineId}/shipments})은 출고 쪽에 있다. 라인 자체는 주문이
 * 지역을 보고 배정하는 대상이라 주문 쪽에 둔다.
 */
@RestController
@RequestMapping("/api/v1/lines")
public class LineController {

    private final LineService lineService;

    public LineController(LineService lineService) {
        this.lineService = lineService;
    }

    @GetMapping
    public LineListResponse lines() {
        return new LineListResponse(lineService.findAll());
    }
}
