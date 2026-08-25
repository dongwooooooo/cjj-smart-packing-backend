package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.service.MeasurementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 촬영·추론 (docs/02-api-spec.md 1-3). */
@Tag(name = "입고 - 측정")
@RestController
@RequestMapping("/api/v1/inbound/measurements")
public class MeasurementController {

    private final MeasurementService measurementService;

    public MeasurementController(MeasurementService measurementService) {
        this.measurementService = measurementService;
    }

    @Operation(summary = "촬영·추론",
            description = "카메라 3대 촬영과 저울 무게 수집 후 추론 결과까지 한 응답으로 돌려준다(동기, 최대 8초). "
                    + "타임아웃·실패는 HTTP 에러가 아니라 status=MEASURE_FAILED 로 내려가며, "
                    + "프론트는 수동 입력 fallback 을 연다. 같은 productId 로 재호출하면 재촬영이다.")
    @PostMapping
    public MeasurementResponse measure(@Valid @RequestBody MeasurementRequest request) {
        return measurementService.measure(request.productId());
    }

    @Operation(summary = "측정 확정",
            description = "APPROVE 는 추론값을 그대로 확정하며 게이트 통과 세션에만 쓸 수 있다. "
                    + "MANUAL 은 요청 치수로 확정하고 MEASURE_FAILED 세션에도 허용된다. "
                    + "무게는 요청값이 세션 저울값보다 우선하고, 둘 다 없으면 400 이다. "
                    + "재고는 변동하지 않는다 (D-09).")
    @PostMapping("/{sessionId}/confirm")
    public ConfirmResponse confirm(@PathVariable Long sessionId,
                                   @Valid @RequestBody ConfirmRequest request) {
        return measurementService.confirm(sessionId, request);
    }
}
