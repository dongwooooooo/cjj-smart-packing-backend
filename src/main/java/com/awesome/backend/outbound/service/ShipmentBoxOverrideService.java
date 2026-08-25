package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.outbound.controller.BoxOverrideResponse;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 박스 오버라이드. docs/02-api-spec.md 3-3.
 *
 * <p>상태 제약 없이 어떤 shipment.status에서도 오버라이드를 허용한다 — 스펙·로드맵에 "이
 * 상태에서만 가능"이라는 제약이 없다. 재검수 큐 연동은 보류 상태라 여기서는 처리하지 않는다
 * ({@code recommendedBoxId != finalBoxId}로 오버라이드 사실만 보존).
 */
@Service
@Transactional
public class ShipmentBoxOverrideService {

    private final ShipmentRepository shipmentRepository;
    private final BoxTypeRepository boxTypeRepository;

    public ShipmentBoxOverrideService(ShipmentRepository shipmentRepository, BoxTypeRepository boxTypeRepository) {
        this.shipmentRepository = shipmentRepository;
        this.boxTypeRepository = boxTypeRepository;
    }

    public BoxOverrideResponse override(Long shipmentId, Long boxTypeId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHIPMENT_NOT_FOUND,
                        "존재하지 않는 shipmentId 입니다: " + shipmentId));

        if (!boxTypeRepository.existsById(boxTypeId)) {
            throw new ApiException(ErrorCode.BOX_TYPE_NOT_FOUND,
                    "존재하지 않는 boxTypeId 입니다: " + boxTypeId);
        }

        shipment.overrideBox(boxTypeId);

        return new BoxOverrideResponse(shipment.id(), shipment.recommendedBoxId(), shipment.finalBoxId());
    }
}
