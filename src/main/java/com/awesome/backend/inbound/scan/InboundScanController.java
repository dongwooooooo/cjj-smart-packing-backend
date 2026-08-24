package com.awesome.backend.inbound.scan;

import com.awesome.backend.inbound.scan.dto.ManualProductRequest;
import com.awesome.backend.inbound.scan.dto.ProductSummary;
import com.awesome.backend.inbound.scan.dto.ScanRequest;
import com.awesome.backend.inbound.scan.dto.ScanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 입고 스캔·수기 등록 (docs/02-api-spec.md 1-1, 1-2). */
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
                    + "NEW 는 이 시점에 product 를 생성한다.")
    @PostMapping("/scans")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return inboundScanService.scan(request.barcode());
    }

    @Operation(summary = "임시 마스터 생성",
            description = "미등록 바코드를 수기 등록한다. 분류는 코드로 받는다 (드롭다운은 GET /categories).")
    @PostMapping("/products")
    public ResponseEntity<ProductSummary> createManual(@Valid @RequestBody ManualProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inboundScanService.createManual(request));
    }
}
