package com.awesome.backend.outbound.controller;

import com.awesome.backend.outbound.service.LineShipmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라인별 배송단위(shipment) 목록 API. docs/02-api-spec.md 3-1 (D-12).
 */
@RestController
@RequestMapping("/api/v1/lines")
public class LineShipmentController {

    private final LineShipmentService lineShipmentService;

    public LineShipmentController(LineShipmentService lineShipmentService) {
        this.lineShipmentService = lineShipmentService;
    }

    @GetMapping("/{lineId}/shipments")
    public LineShipmentListResponse shipments(
            @PathVariable Long lineId,
            @RequestParam(required = false) String status) {
        return new LineShipmentListResponse(lineShipmentService.findByLine(lineId, status));
    }
}
