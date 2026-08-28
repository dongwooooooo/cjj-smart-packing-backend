package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoServedTote;
import com.awesome.backend.demo.repository.DemoServedToteRepository;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포장 시연에서 다음에 스캔할 토트 바코드를 하나씩 내준다.
 *
 * <p>시연장에는 바코드 스캐너가 없다. 화면의 버튼이 이 통로로 바코드를 받아 스캔 칸을
 * 채우고, 작업자는 포장만 한다. 입고 쪽 바코드 통로와 같은 방식이다.
 *
 * <p>내준 토트는 표시해 둔다. "포장 전인 배송단위" 로만 판단하면 작업자가 포장을
 * 시작하기 전에 버튼을 다시 눌렀을 때 같은 토트가 계속 나온다. 리셋이 표시를 비우므로
 * 다시 처음부터 나온다.
 */
@Component
public class DemoToteFeeder {

    /** 아직 포장이 끝나지 않았고 토트가 붙어 있는 상태 — 스캔 대상이 되는 배송단위. */
    private static final List<Shipment.Status> PACKABLE =
            List.of(Shipment.Status.TOTE_ASSIGNED, Shipment.Status.PACKING);

    private final ShipmentRepository shipmentRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;
    private final ToteRepository toteRepository;
    private final OrderRepository orderRepository;
    private final DemoServedToteRepository servedTotes;

    public DemoToteFeeder(ShipmentRepository shipmentRepository,
                          ToteAssignmentRepository toteAssignmentRepository,
                          ToteRepository toteRepository, OrderRepository orderRepository,
                          DemoServedToteRepository servedTotes) {
        this.shipmentRepository = shipmentRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
        this.toteRepository = toteRepository;
        this.orderRepository = orderRepository;
        this.servedTotes = servedTotes;
    }

    /** 내줄 토트가 없으면 빈 값 — 호출자는 204 로 답한다. */
    @Transactional
    public Optional<DemoTote> next(Long lineId) {
        List<Shipment> pending = pendingShipments(lineId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        Shipment target = pending.getFirst();
        String barcode = toteBarcode(target.id()).orElse(null);
        if (barcode == null) {
            // 토트가 안 붙은 배송단위는 스캔할 게 없다. 표시만 남기고 다음 호출로 넘긴다
            servedTotes.save(new DemoServedTote(target.id()));
            return next(lineId);
        }
        servedTotes.save(new DemoServedTote(target.id()));

        String receiptNo = orderRepository.findById(target.orderId())
                .map(Order::receiptNo)
                .orElse(null);
        return Optional.of(new DemoTote(barcode, target.id(), receiptNo, pending.size() - 1));
    }

    /** 이 라인에서 아직 내주지 않은, 포장 전 배송단위를 만들어진 순서대로. */
    private List<Shipment> pendingShipments(Long lineId) {
        Set<Long> alreadyServed = servedTotes.findAll().stream()
                .map(DemoServedTote::shipmentId)
                .collect(Collectors.toUnmodifiableSet());

        List<Shipment> pending = new ArrayList<>();
        for (Shipment.Status status : PACKABLE) {
            pending.addAll(
                    shipmentRepository.findByLineIdAndStatusOrderByCreatedAtAscIdAsc(lineId, status));
        }
        return pending.stream()
                .filter(shipment -> !alreadyServed.contains(shipment.id()))
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .toList();
    }

    private Optional<String> toteBarcode(Long shipmentId) {
        return toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipmentId)
                .map(ToteAssignment::toteId)
                .flatMap(toteRepository::findById)
                .map(Tote::barcode);
    }

    /**
     * @param remaining 이 토트를 뺀 나머지 — 화면이 "4개 중 3개 남음" 을 보여줄 수 있다
     */
    public record DemoTote(String toteBarcode, Long shipmentId, String receiptNo, int remaining) {
    }
}
