package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 리셋 후 토트를 처음부터 다시 내주는지.
 *
 * <p>이 클래스는 테스트 트랜잭션을 쓰지 않는다. 리셋이 커밋된 상태에서 다시 시연을
 * 돌려야 하는데, 테스트가 트랜잭션을 들고 있으면 리셋의 삭제와 그 앞 접수의 저장이
 * 한 트랜잭션에 섞여 실제 순서대로 일어나지 않는다.
 */
@SpringBootTest
@Testcontainers
class DemoNextToteResetIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
    private static final String NEXT_ORDER = "/api/v1/admin/demo/orders/next";
    private static final String NEXT_TOTE = "/api/v1/admin/demo/outbound/next-tote";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from demo_served_tote");
        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from demo_order_queue");
        jdbcTemplate.update("delete from inventory_tx");
        jdbcTemplate.update("update tote set status = 'IDLE'");
    }

    private List<String> runOnceAndDrain() throws Exception {
        mvc.perform(post(RESET)).andExpect(status().isOk());
        while (mvc.perform(post(NEXT_ORDER)).andReturn().getResponse().getStatus() == 200) {
            // 대기열이 빌 때까지 투입
        }
        Long lineId = jdbcTemplate.queryForObject("""
                select line_id from shipment
                 where status in ('TOTE_ASSIGNED', 'PACKING')
                 group by line_id order by count(*) desc, line_id asc limit 1
                """, Long.class);

        List<String> served = new ArrayList<>();
        while (true) {
            MvcResult result = mvc.perform(post(NEXT_TOTE).param("lineId", String.valueOf(lineId)))
                    .andReturn();
            if (result.getResponse().getStatus() == 204) {
                return served;
            }
            served.add(result.getResponse().getContentAsString()
                    .replaceAll(".*\"toteBarcode\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        }
    }

    @Test
    void 리셋하면_처음부터_다시_내준다() throws Exception {
        List<String> firstRound = runOnceAndDrain();
        assertThat(firstRound).isNotEmpty();

        List<String> secondRound = runOnceAndDrain();

        assertThat(secondRound).hasSameSizeAs(firstRound);
        assertThat(secondRound).doesNotHaveDuplicates();
    }
}
