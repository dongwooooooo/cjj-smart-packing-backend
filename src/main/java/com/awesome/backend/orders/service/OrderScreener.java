package com.awesome.backend.orders.service;

import com.awesome.backend.inventory.service.InventoryService;
import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.packing.OversizedItemException;
import com.awesome.backend.orders.packing.ShipmentPlan;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.orders.repository.RegionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 명세 §3 2층 — 주문별 심사. 배치 전체를 막는 1층과 달리 문제 있는 주문만 걸러내고
 * 나머지는 통과시킨다 (부분 성공).
 *
 * <p>검사 순서는 지역 → 라인 → 재고 → 편성이다. 앞 단계에서 거부된 주문은 뒤 단계를
 * 보지 않는다. 재고 누적은 편성까지 통과한 주문만 반영한다 — 초과 치수로 거부될 주문이
 * 앞에서 재고를 잡아버리면, 저장되지도 않은 주문 때문에 뒤 주문이 밀린다.
 *
 * <p>재고는 여기서 실제로 줄이지 않는다. 차감은 포장완료 시점이고, 접수에서는 할당
 * 가능 여부만 본다. 대신 배치 안에서는 주문 순서대로 수량을 누적해, 같은 배치의 앞
 * 주문이 이미 가져간 만큼을 뒤 주문의 잔여량에서 뺀다.
 */
@Component
public class OrderScreener {

    private final RegionRepository regionRepository;
    private final LineRepository lineRepository;
    private final InventoryService inventoryService;

    public OrderScreener(RegionRepository regionRepository, LineRepository lineRepository,
                         InventoryService inventoryService) {
        this.regionRepository = regionRepository;
        this.lineRepository = lineRepository;
        this.inventoryService = inventoryService;
    }

    /** 통과 주문과 거부 주문을 갈라 돌려준다. 입력 순서는 통과 목록에 그대로 보존된다. */
    @Transactional(readOnly = true)
    public Screening screen(OrderImportCommand command, PackingPlanner.Plans plans) {
        Set<String> knownRegions = regionRepository.findAll().stream()
                .map(region -> region.code())
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Integer> remainingStock = new HashMap<>();
        Map<String, Optional<Long>> lineByRegion = new HashMap<>();

        List<AcceptedOrder> accepted = new ArrayList<>();
        List<OrderImportResult.Rejection> rejected = new ArrayList<>();

        for (OrderImportCommand.OrderLine order : command.orders()) {
            if (!knownRegions.contains(order.regionCode())) {
                rejected.add(reject(order, RejectionReason.UNKNOWN_REGION,
                        Map.of("regionCode", order.regionCode())));
                continue;
            }
            Optional<Long> lineId = lineByRegion.computeIfAbsent(order.regionCode(), this::activeLine);
            if (lineId.isEmpty()) {
                rejected.add(reject(order, RejectionReason.NO_ACTIVE_LINE,
                        Map.of("regionCode", order.regionCode())));
                continue;
            }
            Map<String, Integer> demand = demandByGtin(order);
            Optional<OrderImportResult.Rejection> shortage = findShortage(order, demand, remainingStock);
            if (shortage.isPresent()) {
                rejected.add(shortage.get());
                continue;
            }
            List<ShipmentPlan> shipmentPlans;
            try {
                shipmentPlans = plans.of(order);
            } catch (OversizedItemException e) {
                rejected.add(reject(order, RejectionReason.OVERSIZED_ITEM, Map.of("gtin", e.gtin())));
                continue;
            }
            demand.forEach((gtin, qty) -> remainingStock.merge(gtin, -qty, Integer::sum));
            accepted.add(new AcceptedOrder(order, lineId.get(), shipmentPlans));
        }
        return new Screening(List.copyOf(accepted), List.copyOf(rejected));
    }

    /** 같은 지역에 활성 라인이 여럿이면 ID 오름차순 첫 라인 (명세 §5 임시 규칙). */
    private Optional<Long> activeLine(String regionCode) {
        return lineRepository.findByRegionCodeAndStatusOrderByIdAsc(regionCode, Line.Status.ACTIVE)
                .stream()
                .findFirst()
                .map(Line::id);
    }

    /** 한 주문 안에 같은 상품이 여러 줄로 나뉘어 올 수 있다. 판단은 합계로 한다. */
    private Map<String, Integer> demandByGtin(OrderImportCommand.OrderLine order) {
        Map<String, Integer> demand = new LinkedHashMap<>();
        for (OrderImportCommand.ItemLine item : order.items()) {
            demand.merge(item.gtin(), item.qty(), Integer::sum);
        }
        return demand;
    }

    /**
     * 부족한 상품이 하나라도 있으면 주문 전체를 거부한다 — 부분 출고는 없다.
     * 상품 순서는 요청에 실린 순서를 따르므로 같은 입력이면 같은 사유가 나온다.
     */
    private Optional<OrderImportResult.Rejection> findShortage(
            OrderImportCommand.OrderLine order, Map<String, Integer> demand,
            Map<String, Integer> remainingStock) {

        for (Map.Entry<String, Integer> entry : demand.entrySet()) {
            String gtin = entry.getKey();
            int requested = entry.getValue();
            int available = remainingStock.computeIfAbsent(gtin, inventoryService::availableQty);
            if (requested > available) {
                return Optional.of(reject(order, RejectionReason.INSUFFICIENT_STOCK,
                        Map.of("gtin", gtin, "requested", requested, "available", available)));
            }
        }
        return Optional.empty();
    }

    private OrderImportResult.Rejection reject(OrderImportCommand.OrderLine order,
                                              RejectionReason reason, Map<String, Object> detail) {
        return new OrderImportResult.Rejection(order.receiptNo(), reason.name(), detail);
    }

    /** 편성까지 통과한 주문 — 배정된 라인과 편성 결과를 함께 들고 저장 단계로 넘어간다. */
    public record AcceptedOrder(OrderImportCommand.OrderLine order, long lineId,
                                List<ShipmentPlan> plans) {
    }

    public record Screening(List<AcceptedOrder> accepted, List<OrderImportResult.Rejection> rejected) {
    }
}
