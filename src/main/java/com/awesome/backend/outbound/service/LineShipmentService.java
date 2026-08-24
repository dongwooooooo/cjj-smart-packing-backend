package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.controller.LineShipmentResponse;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라인별 배송단위(shipment) 목록 조회. docs/02-api-spec.md 3-1 (D-12).
 *
 * <p>존재하지 않는 lineId는 별도 에러 없이 빈 리스트를 반환한다 — line 존재 검증은 스펙 범위 밖이라
 * 하지 않는다. 연관 order·활성 tote_assignment·tote는 shipment 건마다 개별 조회하지 않고 배치로
 * 모아 온 뒤 Map으로 묶는다(N+1 회피).
 */
@Service
@Transactional(readOnly = true)
public class LineShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;
    private final ToteRepository toteRepository;

    public LineShipmentService(
            ShipmentRepository shipmentRepository,
            OrderRepository orderRepository,
            ToteAssignmentRepository toteAssignmentRepository,
            ToteRepository toteRepository) {
        this.shipmentRepository = shipmentRepository;
        this.orderRepository = orderRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
        this.toteRepository = toteRepository;
    }

    public List<LineShipmentResponse> findByLine(Long lineId, String status) {
        List<Shipment> shipments = (status == null)
                ? shipmentRepository.findByLineIdOrderByCreatedAtAscIdAsc(lineId)
                : shipmentRepository.findByLineIdAndStatusOrderByCreatedAtAscIdAsc(lineId, parseStatus(status));

        if (shipments.isEmpty()) {
            return List.of();
        }

        Map<Long, Order> ordersById = orderRepository
                .findAllById(shipments.stream().map(Shipment::orderId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Order::id, Function.identity()));

        List<ToteAssignment> activeAssignments = toteAssignmentRepository.findByShipmentIdInAndReleasedAtIsNull(
                shipments.stream().map(Shipment::id).toList());

        Map<Long, Tote> totesById = toteRepository
                .findAllById(activeAssignments.stream().map(ToteAssignment::toteId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Tote::id, Function.identity()));

        Map<Long, String> toteBarcodeByShipmentId = activeAssignments.stream()
                .collect(Collectors.toMap(
                        ToteAssignment::shipmentId,
                        assignment -> totesById.get(assignment.toteId()).barcode()));

        return shipments.stream()
                .map(shipment -> new LineShipmentResponse(
                        shipment.id(),
                        ordersById.get(shipment.orderId()).receiptNo(),
                        shipment.seqNo(),
                        shipment.status().name(),
                        toteBarcodeByShipmentId.get(shipment.id())))
                .toList();
    }

    /**
     * {@code @RequestParam}을 곧바로 {@code Shipment.Status}로 받으면 바인딩 실패 시
     * {@code MethodArgumentTypeMismatchException}이 발생하는데 GlobalExceptionHandler가 이를
     * 처리하지 않아 500으로 샌다. String으로 받아 여기서 직접 변환하고, 실패하면 VALIDATION_ERROR로
     * 명시적으로 400을 던진다.
     */
    private Shipment.Status parseStatus(String status) {
        try {
            return Shipment.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "유효하지 않은 status 값입니다: " + status);
        }
    }
}
