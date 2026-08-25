package com.awesome.backend.demo.service;

import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이전 런 종결 (명세 §4-1). 아무것도 지우지 않는다 — 끝난 것으로 표시만 한다.
 *
 * <p>취소 상태가 스키마에 없어서 진행 중이던 주문·배송단위는 LOADED로 닫는다.
 * 시연 범위 밖의 종료 상태를 빌려 쓰는 것이고, 지난 런의 행이 새 런의 화면에
 * 섞여 보이지 않게 하는 게 목적이다.
 */
@Component
public class DemoStateResetter {

    private final JdbcTemplate jdbcTemplate;
    private final MeasurementSessionRepository measurementSessionRepository;

    public DemoStateResetter(JdbcTemplate jdbcTemplate,
                             MeasurementSessionRepository measurementSessionRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.measurementSessionRepository = measurementSessionRepository;
    }

    @Transactional
    public void closePreviousRun() {
        jdbcTemplate.update("""
                update orders set status = 'LOADED'
                 where id in (select order_id from shipment
                               where status in ('PLANNED', 'TOTE_ASSIGNED', 'PACKING'))
                """);
        jdbcTemplate.update("""
                update shipment set status = 'LOADED'
                 where status in ('PLANNED', 'TOTE_ASSIGNED', 'PACKING')
                """);
        jdbcTemplate.update("update tote_assignment set released_at = now() where released_at is null");
        jdbcTemplate.update("update tote set status = 'IDLE' where status <> 'IDLE'");

        // 폐기 전이는 P1 엔티티가 이미 갖고 있다 — 여기서 상태 문자열을 다시 정의하지 않는다
        measurementSessionRepository.findAll().stream()
                .filter(MeasurementSession::isOpen)
                .forEach(MeasurementSession::discard);
    }

    /** 박스 재고 복원 (명세 §4-2). 시연 중 줄어든 값을 seed 수준으로 되돌린다. */
    @Transactional
    public int restoreBoxStock(int stockQty) {
        return jdbcTemplate.update("update box_type set stock_qty = ?", stockQty);
    }
}
