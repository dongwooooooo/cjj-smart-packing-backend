package com.awesome.backend.demo.service;

import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import com.awesome.backend.outbound.service.ShipmentCompleteService;
import com.awesome.backend.outbound.service.ToteScanService;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리셋 끝에 라인마다 몇 건을 미리 포장해 둔다.
 *
 * <p>시연을 열었을 때 라인에 남은 것만 있으면 화면이 방금 시작한 것처럼 보인다. 끝난 것과
 * 남은 것이 함께 있어야 일하는 중인 창고로 읽힌다.
 *
 * <p>상태만 손으로 바꾸지 않고 실제 포장 경로를 그대로 태운다 — 토트를 스캔하고 포장을
 * 끝내는 길이다. 그래야 재고가 실제로 빠지고 토트가 대기로 돌아가, 시연 중에 화면이 보여
 * 주는 수치와 어긋나지 않는다.
 */
@Component
public class DemoPrepacker {

    private static final Logger log = LoggerFactory.getLogger(DemoPrepacker.class);

    private final LineRepository lineRepository;
    private final ShipmentRepository shipmentRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;
    private final ToteRepository toteRepository;
    private final ToteScanService toteScanService;
    private final ShipmentCompleteService completeService;

    public DemoPrepacker(LineRepository lineRepository, ShipmentRepository shipmentRepository,
                         ToteAssignmentRepository toteAssignmentRepository,
                         ToteRepository toteRepository, ToteScanService toteScanService,
                         ShipmentCompleteService completeService) {
        this.lineRepository = lineRepository;
        this.shipmentRepository = shipmentRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
        this.toteRepository = toteRepository;
        this.toteScanService = toteScanService;
        this.completeService = completeService;
    }

    /**
     * @param perLine 라인마다 미리 포장할 건수. 0 이면 아무것도 하지 않는다.
     * @return 실제로 포장을 끝낸 건수. 포장할 것이 모자라면 있는 만큼만 한다.
     */
    @Transactional
    public int prepack(int perLine) {
        if (perLine <= 0) {
            return 0;
        }

        int packed = 0;
        for (Line line : lineRepository.findAllByOrderByIdAsc()) {
            packed += prepackLine(line.id(), perLine);
        }
        return packed;
    }

    private int prepackLine(Long lineId, int perLine) {
        // 오래된 것부터 끝낸다 — 먼저 들어온 주문이 먼저 나간 모양이 된다.
        List<Shipment> waiting = shipmentRepository
                .findByLineIdAndStatusOrderByCreatedAtAscIdAsc(lineId, Shipment.Status.TOTE_ASSIGNED);

        int packed = 0;
        for (Shipment shipment : waiting) {
            if (packed == perLine) {
                break;
            }
            if (pack(shipment)) {
                packed++;
            }
        }
        return packed;
    }

    /**
     * 한 건을 스캔해서 포장까지 끝낸다.
     *
     * <p>재고가 모자라거나 토트가 없는 건은 건너뛴다 — 시연을 준비하다 리셋 자체가 실패하면
     * 아무것도 못 보여준다. 건너뛴 건은 남은 것으로 화면에 그대로 보인다.
     */
    private boolean pack(Shipment shipment) {
        Optional<String> barcode = toteAssignmentRepository
                .findByShipmentIdAndReleasedAtIsNull(shipment.id())
                .map(ToteAssignment::toteId)
                .flatMap(toteRepository::findById)
                .map(tote -> tote.barcode());
        if (barcode.isEmpty()) {
            return false;
        }

        try {
            toteScanService.scan(barcode.get());
            completeService.complete(shipment.id());
            return true;
        } catch (RuntimeException e) {
            log.warn("미리 포장을 건너뛴다. shipmentId={} 이유={}", shipment.id(), e.toString());
            return false;
        }
    }
}
