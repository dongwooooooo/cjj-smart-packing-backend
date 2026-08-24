package com.awesome.backend.orders.service;

import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.entity.OrderItem;
import com.awesome.backend.orders.packing.PackItem;
import com.awesome.backend.orders.packing.ShipmentPlan;
import com.awesome.backend.orders.repository.OrderItemRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.service.ShipmentDraft;
import com.awesome.backend.outbound.service.ShipmentRegistrar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 심사를 통과한 주문을 저장한다 (명세 §6). 호출자의 트랜잭션에 참여하므로
 * 중간에 실패하면 배치 전체가 함께 되돌아간다 — 부분 커밋은 없다.
 *
 * <p>주문은 접수(RECEIVED)로 만들어 배송단위·토트까지 붙인 뒤 할당완료(ALLOCATED)로
 * 넘긴다. 상태가 곧 "작업자에게 내보낼 준비가 됐는지"라, 토트가 붙기 전에 미리
 * 넘기지 않는다.
 */
@Component
public class OrderImportWriter {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentRegistrar shipmentRegistrar;

    public OrderImportWriter(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             ShipmentRegistrar shipmentRegistrar) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentRegistrar = shipmentRegistrar;
    }

    @Transactional
    public Written write(String batchId, List<OrderScreener.AcceptedOrder> accepted,
                         PackingPlanner.Plans plans) {
        int shipments = 0;
        int splitOrders = 0;
        for (OrderScreener.AcceptedOrder acceptedOrder : accepted) {
            OrderImportCommand.OrderLine line = acceptedOrder.order();
            Order order = orderRepository.save(new Order(line.receiptNo(), line.regionCode(),
                    batchId, line.orderedAt()));
            saveItems(order.id(), line, plans);
            shipmentRegistrar.register(order.id(), acceptedOrder.lineId(),
                    drafts(acceptedOrder.plans(), plans));
            order.allocate();

            shipments += acceptedOrder.plans().size();
            if (acceptedOrder.plans().size() > 1) {
                splitOrders++;
            }
        }
        return new Written(accepted.size(), shipments, splitOrders);
    }

    /** 요청이 같은 상품을 여러 줄로 보내도 주문항목은 상품당 한 행으로 합친다. */
    private void saveItems(Long orderId, OrderImportCommand.OrderLine line,
                           PackingPlanner.Plans plans) {
        Map<String, Integer> qtyByGtin = new LinkedHashMap<>();
        for (OrderImportCommand.ItemLine item : line.items()) {
            qtyByGtin.merge(item.gtin(), item.qty(), Integer::sum);
        }
        qtyByGtin.forEach((gtin, qty) ->
                orderItemRepository.save(new OrderItem(orderId, plans.productId(gtin), qty)));
    }

    /** 편성 결과의 낱개 목록을 상품별 수량으로 접어 저장 형태로 옮긴다. */
    private List<ShipmentDraft> drafts(List<ShipmentPlan> shipmentPlans, PackingPlanner.Plans plans) {
        List<ShipmentDraft> drafts = new ArrayList<>();
        int seqNo = 1;
        for (ShipmentPlan plan : shipmentPlans) {
            Map<String, Integer> qtyByGtin = new LinkedHashMap<>();
            for (PackItem item : plan.items()) {
                qtyByGtin.merge(item.gtin(), 1, Integer::sum);
            }
            List<ShipmentDraft.ItemDraft> items = qtyByGtin.entrySet().stream()
                    .map(e -> new ShipmentDraft.ItemDraft(plans.productId(e.getKey()), e.getValue()))
                    .toList();
            drafts.add(new ShipmentDraft(seqNo++, plan.boxId(), plan.fillerRecommended(), items));
        }
        return drafts;
    }

    public record Written(int orders, int shipments, int splitOrders) {
    }
}
