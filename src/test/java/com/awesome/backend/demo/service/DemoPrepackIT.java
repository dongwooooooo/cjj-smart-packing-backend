package com.awesome.backend.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 리셋이 라인마다 몇 건을 미리 포장해 둔다.
 *
 * <p>시연을 열었을 때 끝난 것과 남은 것이 함께 보여야 일하는 중인 창고로 읽힌다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class DemoPrepackIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    @Autowired DemoResetService resetService;
    @Autowired DemoDataProperties properties;
    @Autowired LineRepository lineRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private List<Shipment> byStatus(Long lineId, Shipment.Status status) {
        return shipmentRepository.findByLineIdAndStatusOrderByCreatedAtAscIdAsc(lineId, status);
    }

    @Test
    void 라인마다_설정한_만큼_포장을_끝내_둔다() {
        resetService.reset();

        int perLine = properties.prepackedShipments();
        assertThat(perLine).isPositive();

        for (Line line : lineRepository.findAllByOrderByIdAsc()) {
            List<Shipment> packed = byStatus(line.id(), Shipment.Status.PACKED);
            List<Shipment> waiting = byStatus(line.id(), Shipment.Status.TOTE_ASSIGNED);
            // 그 라인에 포장할 것이 모자라면 있는 만큼만 끝낸다
            assertThat(packed.size()).isLessThanOrEqualTo(perLine);
            if (!waiting.isEmpty()) {
                assertThat(packed).hasSize(perLine);
            }
        }
    }

    @Test
    void 끝난_것과_남은_것이_함께_보인다() {
        resetService.reset();

        List<Shipment> all = shipmentRepository.findAll();
        assertThat(all).anyMatch(s -> s.status() == Shipment.Status.PACKED);
        assertThat(all).anyMatch(s -> s.status() == Shipment.Status.TOTE_ASSIGNED);
    }

    @Test
    void 미리_포장한_만큼_재고가_실제로_빠진다() {
        resetService.reset();

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from inventory_tx where qty_delta < 0", Integer.class);
        assertThat(ledgerRows).isPositive();
    }

    @Test
    void 포장을_끝낸_토트는_대기로_돌아간다() {
        resetService.reset();

        Integer activeAssignments = jdbcTemplate.queryForObject(
                "select count(*) from tote_assignment where released_at is null", Integer.class);
        int assigned = toteRepository.findAll().stream()
                .filter(tote -> tote.status() == Tote.Status.ASSIGNED)
                .toList().size();
        assertThat(assigned).isEqualTo(activeAssignments);
    }
}
