package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.service.StockInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 수량 입고 (docs/02-api-spec.md 1-5). */
@Tag(name = "입고 - 수량")
@RestController
@RequestMapping("/api/v1/inbound/stock-in")
public class StockInController {

    private final StockInService stockInService;

    public StockInController(StockInService stockInService) {
        this.stockInService = stockInService;
    }

    @Operation(summary = "수량 입고",
            description = "촬영분을 포함한 전체 수량을 한 번에 입고한다. 재고 증가의 유일한 경로다 (D-09). "
                    + "측정 확정(1-4)은 재고를 변동시키지 않으므로, 신규 상품도 확정 후 이 API 로 수량을 넣는다.")
    @PostMapping
    public StockInResponse stockIn(@Valid @RequestBody StockInRequest request) {
        return stockInService.stockIn(request.productId(), request.qty());
    }
}
