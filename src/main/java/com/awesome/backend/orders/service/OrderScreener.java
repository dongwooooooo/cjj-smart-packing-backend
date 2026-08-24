package com.awesome.backend.orders.service;

import com.awesome.backend.inventory.service.InventoryService;
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
 * 명세 §3 2층 — 주문별 검증. 배치 전체를 막는 1층과 달리 문제 있는 주문만 걸러내고
 * 나머지는 통과시킨다 (부분 성공).
 *
 * <p>재고는 여기서 실제로 줄이지 않는다. 차감은 포장완료 시점이고, 접수에서는
 * 할당 가능 여부만 본다. 대신 배치 안에서는 주문 순서대로 수량을 누적해,
 * 같은 배치의 앞 주문이 이미 가져간 만큼을 뒤 주문의 잔여량에서 뺀다.
 * 거부된 주문의 수량은 누적에 넣지 않는다 — 저장되지 않으므로 재고를 잡지도 않는다.
 */
@Component
public class OrderScreener {

    private final RegionRepository regionRepository;
    private final InventoryService inventoryService;

    public OrderScreener(RegionRepository regionRepository, InventoryService inventoryService) {
        this.regionRepository = regionRepository;
        this.inventoryService = inventoryService;
    }

    /** 통과 주문과 거부 주문을 갈라 돌려준다. 입력 순서는 통과 목록에 그대로 보존된다. */
    @Transactional(readOnly = true)
    public Screening screen(OrderImportCommand command) {
        Set<String> knownRegions = regionRepository.findAll().stream()
                .map(region -> region.code())
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Integer> remainingStock = new HashMap<>();

        List<OrderImportCommand.OrderLine> accepted = new ArrayList<>();
        List<OrderImportResult.Rejection> rejected = new ArrayList<>();

        for (OrderImportCommand.OrderLine order : command.orders()) {
            if (!knownRegions.contains(order.regionCode())) {
                rejected.add(new OrderImportResult.Rejection(order.receiptNo(),
                        RejectionReason.UNKNOWN_REGION.name(),
                        Map.of("regionCode", order.regionCode())));
                continue;
            }
            Map<String, Integer> demand = demandByGtin(order);
            Optional<OrderImportResult.Rejection> shortage = findShortage(order, demand, remainingStock);
            if (shortage.isPresent()) {
                rejected.add(shortage.get());
                continue;
            }
            demand.forEach((gtin, qty) -> remainingStock.merge(gtin, -qty, Integer::sum));
            accepted.add(order);
        }
        return new Screening(accepted, List.copyOf(rejected));
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
                return Optional.of(new OrderImportResult.Rejection(order.receiptNo(),
                        RejectionReason.INSUFFICIENT_STOCK.name(),
                        Map.of("gtin", gtin, "requested", requested, "available", available)));
            }
        }
        return Optional.empty();
    }

    public record Screening(List<OrderImportCommand.OrderLine> accepted,
                            List<OrderImportResult.Rejection> rejected) {
    }
}
