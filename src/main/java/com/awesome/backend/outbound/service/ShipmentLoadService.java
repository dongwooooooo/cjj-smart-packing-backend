package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.outbound.controller.ShipmentLoadResponse;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 적재 처리 — PACKED → LOADED 상태 전이. docs/02-api-spec.md 3-9. 로드맵 Phase D 마지막, "적재(상태값만
 * — 시연 범위 밖), 가장 단순, 검증도 최소한만"이라 명시된 API라 PACKED 상태 제약(Shipment.load()에
 * 위임) 외 다른 검증은 하지 않는다 — ToteScanService.scan()과 같이 IllegalStateException을
 * INVALID_STATE로 변환하는 패턴을 그대로 따른다.
 */
@Service
@Transactional
public class ShipmentLoadService {

    private final ShipmentRepository shipmentRepository;

    public ShipmentLoadService(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    public ShipmentLoadResponse load(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHIPMENT_NOT_FOUND,
                        "존재하지 않는 shipmentId 입니다: " + shipmentId));

        try {
            shipment.load();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.INVALID_STATE, e.getMessage());
        }

        return new ShipmentLoadResponse(shipment.id(), shipment.status().name());
    }
}
