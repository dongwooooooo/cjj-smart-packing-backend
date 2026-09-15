package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.service.StockMovementRecorder;
import com.awesome.backend.outbound.controller.ShipmentCompleteResponse;
import com.awesome.backend.outbound.entity.BoxType;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포장 완료 처리 (3-8, "제일 중요하고 제일 어려운 API"). docs/02-api-spec.md 3-8.
 *
 * <p><b>왜 전부 한 트랜잭션인가</b>: 상태 전이, 상품 재고 차감(N건), 박스 재고 차감, 토트 할당
 * 해제 중 어느 하나라도 실패하면(예: 세 번째 상품에서 재고 부족) 앞서 성공한 나머지도 전부
 * 롤백돼야 한다 — 안 그러면 "재고는 깎였는데 shipment는 여전히 PACKING" 같은 반쪽짜리 상태가
 * 남는다. {@code @Transactional} 메서드 안에서 unchecked exception({@link ApiException}도
 * {@link RuntimeException}이라 포함)이 새 나가면 Spring이 이 메서드가 시작한 물리 트랜잭션
 * 전체를 자동 롤백하므로, 아래 단계를 전부 이 메서드 하나 안에서 순차 호출하는 것만으로 충분하고
 * 별도 보상(compensation) 코드가 필요 없다.
 */
@Service
@Transactional
public class ShipmentCompleteService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final ProductRepository productRepository;
    private final StockMovementRecorder stockMovementRecorder;
    private final BoxTypeRepository boxTypeRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;
    private final ToteRepository toteRepository;
    private final ShipmentWeightEstimator weightEstimator;

    public ShipmentCompleteService(
            ShipmentRepository shipmentRepository,
            ShipmentItemRepository shipmentItemRepository,
            ProductRepository productRepository,
            StockMovementRecorder stockMovementRecorder,
            BoxTypeRepository boxTypeRepository,
            ToteAssignmentRepository toteAssignmentRepository,
            ToteRepository toteRepository,
            ShipmentWeightEstimator weightEstimator) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentItemRepository = shipmentItemRepository;
        this.productRepository = productRepository;
        this.stockMovementRecorder = stockMovementRecorder;
        this.boxTypeRepository = boxTypeRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
        this.toteRepository = toteRepository;
        this.weightEstimator = weightEstimator;
    }

    /**
     * @param measuredWeightKg 저울에 올려 잰 무게. null이면 무게 검수를 건너뛴다.
     */
    public ShipmentCompleteResponse complete(Long shipmentId, BigDecimal measuredWeightKg) {
        // 1. 조회 — 없으면 404. 이후 모든 단계의 전제.
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHIPMENT_NOT_FOUND,
                        "배송단위를 찾을 수 없습니다: " + shipmentId));

        // 2. 상태 사전 검증 — PACKING이 아니면 재고를 건드리기 전에 가장 싸게 걸러낸다.
        // 스펙 예시는 PACKED·PLANNED 두 경우만 들지만, 실제로는 PACKING이 아닌 모든 상태가
        // 잘못된 호출이라 "PACKING일 때만 허용"으로 검사한다 (스펙의 두 예시를 자연히 포함).
        if (shipment.status() != Shipment.Status.PACKING) {
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "PACKING 상태에서만 포장 완료할 수 있습니다. 현재 상태: " + shipment.status());
        }

        // 3. 무게 검수 — 잰 무게를 보냈고 예상 무게를 낼 수 있을 때만 본다. 재고를 건드리기 전에
        // 걸러야 불일치로 막힌 포장이 재고 이력을 남기지 않는다.
        BigDecimal expectedWeightKg = weightEstimator.expectedKg(shipment).orElse(null);
        if (measuredWeightKg != null && expectedWeightKg != null) {
            weightEstimator.verify(expectedWeightKg, measuredWeightKg);
        }

        // 4. 상품 재고 차감 — inventory_tx(OUTBOUND_PACKED) 기록과 product.stock_qty 갱신은
        // StockMovementRecorder 단일 창구에 위임한다(직접 짜지 않음). 재고 부족이면 이 호출이
        // 곧바로 ApiException(OUT_OF_STOCK)을 던지고, 그 예외가 트랜잭션을 롤백시킨다.
        for (ShipmentItem item : shipmentItemRepository.findByShipmentId(shipmentId)) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "shipment_item이 참조하는 상품을 찾을 수 없습니다: productId=" + item.productId()));
            stockMovementRecorder.recordOutboundPacked(product.gtin(), item.qty(), shipmentId);
        }

        // 5. 박스 재고 차감 — final_box 우선, 없으면 recommended_box. 동시성 보호를 위해 비관적
        // 락 조회(findByIdForUpdate)로 가져온다(ProductRepository.findByGtinForUpdate와 같은 이유:
        // 여러 포장완료 요청이 같은 박스 재고를 동시에 깎을 때 lost update 방지).
        Long boxId = shipment.finalBoxId() != null ? shipment.finalBoxId() : shipment.recommendedBoxId();
        BoxType boxType = boxTypeRepository.findByIdForUpdate(boxId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "shipment가 참조하는 박스 타입을 찾을 수 없습니다: boxTypeId=" + boxId));
        if (boxType.stockQty() <= 0) {
            throw new ApiException(ErrorCode.OUT_OF_STOCK,
                    "박스 재고가 부족합니다.", Map.of("boxTypeId", boxId));
        }
        boxType.decreaseStock();

        // 6. 토트 할당 해제 — PACKING 상태는 항상 활성 tote_assignment가 있어야 정상이므로,
        // 없으면 데이터 정합성이 깨진 것 (ShipmentDetailService의 기존 방어 패턴과 동일).
        ToteAssignment assignment = toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "PACKING 상태인데 활성 토트 할당이 없습니다: shipmentId=" + shipmentId));
        assignment.release();
        Tote tote = toteRepository.findById(assignment.toteId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "tote_assignment가 참조하는 토트를 찾을 수 없습니다: toteId=" + assignment.toteId()));
        tote.release();

        // 7. shipment 상태 전이 — 엔티티 자체 불변식(Shipment.complete())으로 한 번 더 방어한다.
        // IllegalStateException은 2번에서 이미 걸러졌다면 여기서 발생할 수 없지만, 엔티티가 다른
        // 경로로 호출돼도 안전하도록 방어 코드는 유지하고 ApiException(INVALID_STATE)로 변환한다.
        try {
            shipment.complete();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.INVALID_STATE, e.getMessage());
        }

        // 8. line.packedCount — 방금 반영된 shipment 상태 변경까지 포함해 그 즉시 값으로 센다.
        long packedCount = shipmentRepository.countByLineIdAndStatus(shipment.lineId(), Shipment.Status.PACKED);

        // 9. 응답 조립.
        return new ShipmentCompleteResponse(
                shipment.id(),
                shipment.status().name(),
                shipment.packedAt(),
                expectedWeightKg,
                new ShipmentCompleteResponse.Line(shipment.lineId(), packedCount));
    }
}
