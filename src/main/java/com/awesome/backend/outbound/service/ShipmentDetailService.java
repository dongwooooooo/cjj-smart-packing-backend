package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.entity.Category;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.CategoryRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.outbound.controller.BoxTypeResponse;
import com.awesome.backend.outbound.controller.ShipmentDetailResponse;
import com.awesome.backend.outbound.controller.ShipmentDetailResponse.ItemResponse;
import com.awesome.backend.outbound.controller.ShipmentDetailResponse.LineResponse;
import com.awesome.backend.outbound.controller.ShipmentDetailResponse.ToteResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송단위(shipment) 상세 조회 — 박스 추천 화면의 핵심 API. docs/02-api-spec.md 3-2.
 *
 * <p>파생 취급속성(냉장 필수/액체주의/적층불가, docs/03-erd.md §6) 계산도 이 서비스 안에 둔다 —
 * 저장하지 않고 조회 시 계산하는 이 API 전용 로직이라 별도 패키지·클래스로 쪼개지 않는다.
 *
 * <p>line/recommendedBox/finalBox/tote는 shipment 기준 FK가 보장하는 단건 조회라 곧바로
 * {@code orElseThrow}로 처리한다(참조 무결성이 깨진 경우는 INTERNAL_ERROR — ToteAllocator와 같은
 * 패턴). 반면 shipment_item에 딸린 product는 LineShipmentService처럼 배치로 모아 Map으로 묶어
 * N+1을 피한다. 액체주의 판정에 쓰는 category도 마찬가지로 배치 조회하되, 데이터 정합성이
 * 깨져 있어도(분류 코드 참조 끊김 등) 화면 표시 자체가 500으로 막히지 않도록 "해당 없음"으로
 * 완화 처리한다 — 파생 취급속성은 표시용 보조 정보라 엄격한 무결성 검증보다 가용성을 우선한다.
 */
@Service
@Transactional(readOnly = true)
public class ShipmentDetailService {

    private static final String BEVERAGE_LARGE_CATEGORY_NAME = "음료";
    private static final String HANDLING_REFRIGERATE = "REFRIGERATE";
    private static final String HANDLING_LIQUID_CAUTION = "LIQUID_CAUTION";
    private static final String HANDLING_IRREGULAR = "IRREGULAR";

    private final ShipmentRepository shipmentRepository;
    private final LineRepository lineRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;
    private final ToteRepository toteRepository;
    private final BoxTypeRepository boxTypeRepository;
    private final ShipmentItemRepository shipmentItemRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ShipmentWeightEstimator weightEstimator;

    public ShipmentDetailService(
            ShipmentRepository shipmentRepository,
            LineRepository lineRepository,
            ToteAssignmentRepository toteAssignmentRepository,
            ToteRepository toteRepository,
            BoxTypeRepository boxTypeRepository,
            ShipmentItemRepository shipmentItemRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ShipmentWeightEstimator weightEstimator) {
        this.shipmentRepository = shipmentRepository;
        this.lineRepository = lineRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
        this.toteRepository = toteRepository;
        this.boxTypeRepository = boxTypeRepository;
        this.shipmentItemRepository = shipmentItemRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.weightEstimator = weightEstimator;
    }

    public ShipmentDetailResponse find(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHIPMENT_NOT_FOUND,
                        "존재하지 않는 shipmentId 입니다: " + shipmentId));

        Line line = lineRepository.findById(shipment.lineId())
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "shipment.lineId에 해당하는 line이 없습니다: " + shipment.lineId()));

        ToteResponse tote = findActiveTote(shipmentId);

        BoxTypeResponse recommendedBox = findBoxType(shipment.recommendedBoxId());
        BoxTypeResponse finalBox = shipment.finalBoxId() == null ? null : findBoxType(shipment.finalBoxId());

        List<ItemResponse> items = buildItems(shipmentItemRepository.findByShipmentId(shipmentId));

        return new ShipmentDetailResponse(
                shipment.id(),
                shipment.orderId(),
                shipment.seqNo(),
                shipment.status().name(),
                new LineResponse(line.id(), line.name()),
                tote,
                recommendedBox,
                finalBox,
                shipment.fillerRecommended(),
                weightEstimator.expectedKg(shipment).orElse(null),
                items);
    }

    private ToteResponse findActiveTote(Long shipmentId) {
        return toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipmentId)
                .map(ToteAssignment::toteId)
                .map(toteId -> toteRepository.findById(toteId)
                        .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                                "tote_assignment.toteId에 해당하는 tote가 없습니다: " + toteId)))
                .map(t -> new ToteResponse(t.id(), t.barcode()))
                .orElse(null);
    }

    private BoxTypeResponse findBoxType(Long boxTypeId) {
        BoxType boxType = boxTypeRepository.findById(boxTypeId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "boxTypeId에 해당하는 box_type이 없습니다: " + boxTypeId));
        return BoxTypeResponse.from(boxType);
    }

    private List<ItemResponse> buildItems(List<ShipmentItem> shipmentItems) {
        if (shipmentItems.isEmpty()) {
            return List.of();
        }

        Map<Long, Product> productsById = productRepository
                .findAllById(shipmentItems.stream().map(ShipmentItem::productId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<String> fragileCategoryCodes = productsById.values().stream()
                .filter(Product::fragile)
                .map(Product::mediumCategoryCode)
                .distinct()
                .toList();

        Map<String, Category> categoriesByCode = categoryRepository.findAllById(fragileCategoryCodes).stream()
                .collect(Collectors.toMap(Category::getCode, Function.identity()));

        return shipmentItems.stream()
                .map(item -> {
                    Product product = productsById.get(item.productId());
                    return new ItemResponse(
                            product.id(),
                            product.gtin(),
                            product.name(),
                            item.qty(),
                            deriveHandling(product, categoriesByCode));
                })
                .toList();
    }

    private List<String> deriveHandling(Product product, Map<String, Category> categoriesByCode) {
        List<String> handling = new ArrayList<>();
        if (product.refrigerate()) {
            handling.add(HANDLING_REFRIGERATE);
        }
        if (product.fragile() && isBeverage(product, categoriesByCode)) {
            handling.add(HANDLING_LIQUID_CAUTION);
        }
        if (product.irregular()) {
            handling.add(HANDLING_IRREGULAR);
        }
        return handling;
    }

    private boolean isBeverage(Product product, Map<String, Category> categoriesByCode) {
        Category category = categoriesByCode.get(product.mediumCategoryCode());
        return category != null && BEVERAGE_LARGE_CATEGORY_NAME.equals(category.getLargeName());
    }
}
