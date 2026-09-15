package com.awesome.backend.outbound.controller;

import com.awesome.backend.outbound.service.ShipmentBoxOverrideService;
import com.awesome.backend.outbound.service.ShipmentCompleteService;
import com.awesome.backend.outbound.service.ShipmentDetailService;
import com.awesome.backend.outbound.service.ShipmentLoadService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배송단위(shipment) 상세 조회·박스 오버라이드·포장완료·적재 API. docs/02-api-spec.md 3-2, 3-3, 3-8, 3-9.
 *
 * <p>전부 같은 리소스({@code /shipments/{shipmentId}})를 다뤄 한 컨트롤러에 묶는다.
 */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentDetailController {

    private final ShipmentDetailService shipmentDetailService;
    private final ShipmentBoxOverrideService shipmentBoxOverrideService;
    private final ShipmentCompleteService shipmentCompleteService;
    private final ShipmentLoadService shipmentLoadService;

    public ShipmentDetailController(
            ShipmentDetailService shipmentDetailService,
            ShipmentBoxOverrideService shipmentBoxOverrideService,
            ShipmentCompleteService shipmentCompleteService,
            ShipmentLoadService shipmentLoadService) {
        this.shipmentDetailService = shipmentDetailService;
        this.shipmentBoxOverrideService = shipmentBoxOverrideService;
        this.shipmentCompleteService = shipmentCompleteService;
        this.shipmentLoadService = shipmentLoadService;
    }

    @GetMapping("/{shipmentId}")
    public ShipmentDetailResponse shipment(@PathVariable Long shipmentId) {
        return shipmentDetailService.find(shipmentId);
    }

    @PutMapping("/{shipmentId}/box")
    public BoxOverrideResponse overrideBox(
            @PathVariable Long shipmentId, @Valid @RequestBody BoxOverrideRequest request) {
        return shipmentBoxOverrideService.override(shipmentId, request.boxTypeId());
    }

    /**
     * 포장 완료 처리. docs/02-api-spec.md 3-8.
     *
     * <p>본문은 선택이다 — 저울을 쓰지 않는 호출은 본문 없이 그대로 보낸다.
     */
    @PostMapping("/{shipmentId}/complete")
    public ShipmentCompleteResponse complete(
            @PathVariable Long shipmentId,
            @RequestBody(required = false) ShipmentCompleteRequest request) {
        return shipmentCompleteService.complete(shipmentId,
                request == null ? null : request.measuredWeightKg());
    }

    @PutMapping("/{shipmentId}/load")
    public ShipmentLoadResponse load(@PathVariable Long shipmentId) {
        return shipmentLoadService.load(shipmentId);
    }
}
