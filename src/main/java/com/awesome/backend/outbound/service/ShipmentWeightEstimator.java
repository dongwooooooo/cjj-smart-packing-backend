package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.service.PackingProperties;
import com.awesome.backend.outbound.entity.BoxType;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 배송단위 예상 총무게 계산과 출고 무게 검수.
 *
 * <p>예상 총무게 = Σ(상품 무게 × 수량) + 박스 자체 무게. 편성이 요금 구간을 판정할 때 쓴 것과
 * 같은 정의다 — 포장 현장에서 잰 무게가 여기서 크게 벗어나면 다른 물건이 들어갔거나 빠진 것이다.
 *
 * <p>상품 무게가 하나라도 비어 있으면 예상값을 내지 않는다(empty). 비어 있는 무게를 0으로 세면
 * 예상값이 실제보다 가벼워져, 정상 포장을 불일치로 막는 쪽으로 틀어진다.
 */
@Component
public class ShipmentWeightEstimator {

    private static final int WEIGHT_SCALE = 3;

    private final ShipmentItemRepository shipmentItemRepository;
    private final ProductRepository productRepository;
    private final BoxTypeRepository boxTypeRepository;
    private final PackingProperties.WeightCheck weightCheck;

    public ShipmentWeightEstimator(ShipmentItemRepository shipmentItemRepository,
                                   ProductRepository productRepository,
                                   BoxTypeRepository boxTypeRepository,
                                   PackingProperties.WeightCheck weightCheck) {
        this.shipmentItemRepository = shipmentItemRepository;
        this.productRepository = productRepository;
        this.boxTypeRepository = boxTypeRepository;
        this.weightCheck = weightCheck;
    }

    /** 예상 총무게. 상품 무게가 하나라도 비어 있으면 empty. */
    public Optional<BigDecimal> expectedKg(Shipment shipment) {
        List<ShipmentItem> items = shipmentItemRepository.findByShipmentId(shipment.id());
        BigDecimal total = BigDecimal.ZERO;
        for (ShipmentItem item : items) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "shipment_item이 참조하는 상품을 찾을 수 없습니다: productId=" + item.productId()));
            if (product.weightKg() == null) {
                return Optional.empty();
            }
            total = total.add(product.weightKg().multiply(BigDecimal.valueOf(item.qty())));
        }
        return Optional.of(total.add(tareKg(shipment)).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 잰 무게가 예상 무게에서 허용 오차 밖이면 409 WEIGHT_MISMATCH.
     * 허용 오차 = max(packing.weight-check.min-tolerance-kg, 예상 × tolerance-ratio).
     */
    public void verify(BigDecimal expectedKg, BigDecimal measuredKg) {
        BigDecimal tolerance = BigDecimal.valueOf(weightCheck.toleranceKg(expectedKg.doubleValue()))
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal gap = expectedKg.subtract(measuredKg).abs();
        if (gap.compareTo(tolerance) > 0) {
            throw new ApiException(ErrorCode.WEIGHT_MISMATCH,
                    "잰 무게가 예상 무게와 다릅니다. 담긴 물건을 확인하세요.",
                    Map.of("expectedKg", expectedKg, "measuredKg", measuredKg,
                            "toleranceKg", tolerance));
        }
    }

    /** 박스 자체 무게 — 확정 박스가 있으면 그쪽, 없으면 추천 박스 (재고 차감과 같은 기준). */
    private BigDecimal tareKg(Shipment shipment) {
        Long boxId = shipment.finalBoxId() != null ? shipment.finalBoxId() : shipment.recommendedBoxId();
        BoxType box = boxTypeRepository.findById(boxId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "shipment가 참조하는 박스 타입을 찾을 수 없습니다: boxTypeId=" + boxId));
        return box.tareWeightKg();
    }
}
