package com.awesome.backend.orders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.orders.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 명세 §6 — 토트가 모자라면 배치 전체가 되돌아간다.
 *
 * <p>이 클래스만 테스트 트랜잭션을 쓰지 않는다. 테스트가 트랜잭션을 열고 있으면
 * 접수 서비스의 트랜잭션이 거기 참여해버려서, 서비스가 롤백해도 실제로 되돌아가지
 * 않는다 — 되돌아갔는지를 보려면 서비스가 자기 트랜잭션을 커밋·롤백하게 둬야 한다.
 * 대신 남는 데이터는 매 테스트 뒤에 직접 지운다.
 */
@SpringBootTest
@Testcontainers
class OrdersImportToteShortageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/admin/orders/import";
    private static final String CHIP = "8801234500042";

    @Autowired WebApplicationContext context;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.update("""
                update product set width_cm = 5.0, length_cm = 5.0, height_cm = 2.0,
                                   dim_status = 'CONFIRMED', stock_qty = 10
                where gtin = ?
                """, CHIP);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from inventory_tx");
        jdbcTemplate.update("update tote set status = 'IDLE'");
        jdbcTemplate.update("""
                update product set width_cm = null, length_cm = null, height_cm = null,
                                   dim_status = 'NONE', stock_qty = 0
                """);
    }

    @Test
    void 유휴_토트가_없으면_배치_전체를_되돌린다() throws Exception {
        jdbcTemplate.update("update tote set status = 'ASSIGNED'");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP)))
                .andExpect(status().isInternalServerError());

        assertThat(orderRepository.existsByReceiptNo("R-1")).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from shipment", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tote_assignment", Integer.class))
                .isZero();
    }

    @Test
    void 토트가_남는_만큼만_있으면_정상_접수된다() throws Exception {
        jdbcTemplate.update("update tote set status = 'ASSIGNED'");
        jdbcTemplate.update("""
                update tote set status = 'IDLE'
                where id = (select min(id) from tote)
                """);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP)))
                .andExpect(status().isOk());

        assertThat(orderRepository.existsByReceiptNo("R-1")).isTrue();
    }
}
