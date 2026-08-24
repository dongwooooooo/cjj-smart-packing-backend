package com.awesome.backend.outbound.service;

import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송단위 생성 창구. 테이블 주인은 P2지만 생성 시점은 P3의 출고지시 접수라,
 * 만드는 경로를 여기 하나로 모은다 (명세 §6).
 *
 * <p>토트 할당과 상태 전이는 여기서 하지 않는다 — 배송단위는 PLANNED로 만들어지고,
 * 토트가 붙는 건 U4다.
 */
@Service
public class ShipmentRegistrar {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentItemRepository shipmentItemRepository;

    public ShipmentRegistrar(ShipmentRepository shipmentRepository,
                             ShipmentItemRepository shipmentItemRepository) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentItemRepository = shipmentItemRepository;
    }

    /** 한 주문의 배송단위 전부를 저장한다. 호출자의 트랜잭션에 참여한다. */
    @Transactional
    public void register(long orderId, long lineId, List<ShipmentDraft> drafts) {
        for (ShipmentDraft draft : drafts) {
            Shipment shipment = shipmentRepository.save(new Shipment(orderId, draft.seqNo(), lineId,
                    draft.recommendedBoxId(), draft.fillerRecommended()));
            for (ShipmentDraft.ItemDraft item : draft.items()) {
                shipmentItemRepository.save(
                        new ShipmentItem(shipment.id(), item.productId(), item.qty()));
            }
        }
    }
}
