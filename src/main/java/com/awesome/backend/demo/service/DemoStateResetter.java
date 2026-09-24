package com.awesome.backend.demo.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 *
 * <p>지우기 전에 아직 안 나간 쓰기를 먼저 내보낸다. 삭제는 SQL 로 곧바로 나가지만 그 앞의
 * 저장은 뒤늦게 나가기 때문에, 순서가 뒤집히면 방금 지운 자리에 옛 행이 다시 들어가 토트
 * 배정이 겹친다.
 */
@Component
public class DemoStateResetter {

    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public DemoStateResetter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void clearDemoData() {
        entityManager.flush();
        entityManager.clear();

        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from measurement_image");
        jdbcTemplate.update("delete from measurement_session");
        jdbcTemplate.update("delete from inventory_tx");
        // 원장을 비웠으므로 스냅샷도 0 — 실재고 = 스냅샷 + 미집계 원장 이 0 이 된다 (specs/2026-09-23-ledger-stock-design.md 6절 컴포넌트 표, D-L2).
        jdbcTemplate.update("update stock_balance set qty = 0, last_tx_id = 0, computed_at = now()");
        jdbcTemplate.update("delete from demo_order_queue");
        jdbcTemplate.update("delete from demo_served_tote");
    }

    /** 토트는 전부 유휴, 박스 재고는 seed 수준으로 (명세 §4-2). */
    @Transactional
    public void restoreEquipment(int boxStockQty) {
        jdbcTemplate.update("update tote set status = 'IDLE' where status <> 'IDLE'");
        jdbcTemplate.update("update box_type set stock_qty = ?", boxStockQty);
    }
}
