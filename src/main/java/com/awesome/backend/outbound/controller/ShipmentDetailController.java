package com.awesome.backend.outbound.controller;

import com.awesome.backend.outbound.service.ShipmentDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배송단위(shipment) 상세 조회 API — 박스 추천 화면. docs/02-api-spec.md 3-2.
 */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentDetailController {

    private final ShipmentDetailService shipmentDetailService;

    public ShipmentDetailController(ShipmentDetailService shipmentDetailService) {
        this.shipmentDetailService = shipmentDetailService;
    }

    @GetMapping("/{shipmentId}")
    public ShipmentDetailResponse shipment(@PathVariable Long shipmentId) {
        return shipmentDetailService.find(shipmentId);
    }
}
