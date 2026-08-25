package com.awesome.backend.outbound.controller;

import com.awesome.backend.outbound.service.ShipmentBoxOverrideService;
import com.awesome.backend.outbound.service.ShipmentDetailService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배송단위(shipment) 상세 조회·박스 오버라이드 API. docs/02-api-spec.md 3-2, 3-3.
 *
 * <p>둘 다 같은 리소스({@code /shipments/{shipmentId}})를 다뤄 한 컨트롤러에 묶는다.
 */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentDetailController {

    private final ShipmentDetailService shipmentDetailService;
    private final ShipmentBoxOverrideService shipmentBoxOverrideService;

    public ShipmentDetailController(
            ShipmentDetailService shipmentDetailService,
            ShipmentBoxOverrideService shipmentBoxOverrideService) {
        this.shipmentDetailService = shipmentDetailService;
        this.shipmentBoxOverrideService = shipmentBoxOverrideService;
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
}
