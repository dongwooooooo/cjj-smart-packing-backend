package com.awesome.backend.outbound.controller;

import com.awesome.backend.outbound.service.ToteScanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토트 바코드 스캔 API — 포장 화면 진입점. docs/02-api-spec.md 3-5 (D-14).
 */
@RestController
@RequestMapping("/api/v1/totes")
public class ToteScanController {

    private final ToteScanService toteScanService;

    public ToteScanController(ToteScanService toteScanService) {
        this.toteScanService = toteScanService;
    }

    @PostMapping("/scan")
    public ShipmentDetailResponse scan(@Valid @RequestBody ToteScanRequest request) {
        return toteScanService.scan(request.barcode());
    }
}
