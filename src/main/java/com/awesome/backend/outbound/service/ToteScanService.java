package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.outbound.controller.ShipmentDetailResponse;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토트 바코드 스캔 — 포장 화면 진입점. docs/02-api-spec.md 3-5 (D-14).
 *
 * <p>등록되지 않은 바코드와, 등록은 됐지만 활성(released_at IS NULL) 할당이 없는 토트를
 * 구분하지 않고 둘 다 {@code TOTE_NOT_ASSIGNED} 404로 응답한다 — 작업자 입장에서는 "이 토트로
 * 포장 진입 불가"라는 같은 의미고, 스펙에도 별도의 TOTE_NOT_FOUND 코드는 없다.
 *
 * <p>상세 응답은 {@link ShipmentDetailService#find}를 그대로 재사용해 조립한다 — 3-2와 완전히
 * 같은 형태이기 때문이다. 이 클래스가 {@code @Transactional}이라 find() 호출도 기본 전파
 * (REQUIRED)로 같은 트랜잭션에 묶이고, 영속성 컨텍스트 1차 캐시 덕분에 방금 바꾼
 * shipment.status가 flush 없이도 바로 조회 결과에 반영된다.
 */
@Service
@Transactional
public class ToteScanService {

    private final ToteRepository toteRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentDetailService shipmentDetailService;

    public ToteScanService(
            ToteRepository toteRepository,
            ToteAssignmentRepository toteAssignmentRepository,
            ShipmentRepository shipmentRepository,
            ShipmentDetailService shipmentDetailService) {
        this.toteRepository = toteRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
        this.shipmentRepository = shipmentRepository;
        this.shipmentDetailService = shipmentDetailService;
    }

    public ShipmentDetailResponse scan(String barcode) {
        Tote tote = toteRepository.findByBarcode(barcode)
                .orElseThrow(() -> new ApiException(ErrorCode.TOTE_NOT_ASSIGNED,
                        "등록되지 않은 토트 바코드입니다: " + barcode));

        ToteAssignment assignment = toteAssignmentRepository.findByToteIdAndReleasedAtIsNull(tote.id())
                .orElseThrow(() -> new ApiException(ErrorCode.TOTE_NOT_ASSIGNED,
                        "활성 할당이 없는 토트입니다: " + barcode));

        Shipment shipment = shipmentRepository.findById(assignment.shipmentId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "tote_assignment.shipmentId에 해당하는 shipment가 없습니다: " + assignment.shipmentId()));

        try {
            shipment.startPacking();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.INVALID_STATE, e.getMessage());
        }

        return shipmentDetailService.find(shipment.id());
    }
}
