package com.awesome.backend.demo.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시연 잔여물 삭제 (명세 §4-1). 지난 시연에서 만들어진 행을 전부 지우고,
 * 기준정보는 처음 상태로 되돌린다.
 *
 * <p>기준정보(분류·지역·라인·박스·토트 행)는 지우지 않는다. 지우면 seed를 다시 넣어야
 * 하고, 배송단위가 참조하는 라인·박스가 사라진다. 대신 상태만 처음으로 돌린다.
 *
 * <p>삭제 순서는 참조를 거는 쪽부터다 — 품목이 배송단위를, 배송단위가 주문을,
 * 이미지가 측정 세션을 참조한다. 순서를 뒤집으면 외래키에 걸린다.
 */
@Component
public class DemoStateResetter {

    private final JdbcTemplate jdbcTemplate;

    public DemoStateResetter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void clearDemoData() {
        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from measurement_image");
        jdbcTemplate.update("delete from measurement_session");
        jdbcTemplate.update("delete from inventory_tx");
        jdbcTemplate.update("delete from demo_order_queue");
    }

    /** 토트는 전부 유휴, 박스 재고는 seed 수준으로 (명세 §4-2). */
    @Transactional
    public void restoreEquipment(int boxStockQty) {
        jdbcTemplate.update("update tote set status = 'IDLE' where status <> 'IDLE'");
        jdbcTemplate.update("update box_type set stock_qty = ?", boxStockQty);
    }
}
