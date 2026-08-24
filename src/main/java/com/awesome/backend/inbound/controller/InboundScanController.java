package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.service.InboundScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 입고 스캔 (docs/02-api-spec.md 1-1). */
@Tag(name = "입고 - 스캔")
@RestController
@RequestMapping("/api/v1/inbound")
public class InboundScanController {

    private final InboundScanService inboundScanService;

    public InboundScanController(InboundScanService inboundScanService) {
        this.inboundScanService = inboundScanService;
    }

    @Operation(summary = "바코드 스캔",
            description = "REGISTERED(치수 O) / NEW(마스터 O, 치수 X) / UNKNOWN(마스터 X) 3분기 판정. "
                    + "NEW 는 이 시점에 product 를 생성한다. UNKNOWN 은 product 가 null 이며, "
                    + "프론트는 마스터에 없는 상품임을 안내하고 흐름을 종료한다 (D-21).")
    @PostMapping("/scans")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return inboundScanService.scan(request.barcode());
    }
}
