package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 포장 시연에서 다음에 스캔할 토트 바코드를 하나씩 내준다.
 *
 * <p>시연장에 스캐너가 없어 화면 버튼이 이 통로로 바코드를 받아 입력칸을 채운다.
 * 입고 쪽 바코드 통로와 같은 방식이다 — 내준 것은 표시해 두고, 리셋이 그 표시를 지운다.
 */
/*
 * 리셋이 미리 투입하는 몫을 끈다. 이 테스트가 보는 것은 주문을 하나씩 넣는 통로 자체라,
 * 대기열이 비어 있는 상태에서 시작해야 몇 번째 묶음이 나가는지 확인할 수 있다.
 * 미리 투입하는 동작은 DemoResetIT 가 본다.
 */
@SpringBootTest(properties = "demo.prereleased-batches=0")
@Testcontainers
@Transactional
class DemoNextToteIT {

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

    /** 배치를 전부 투입해 라인마다 배송단위가 생기게 한다. */
    private void feedAllBatches() throws Exception {
        mvc.perform(post(RESET)).andExpect(status().isOk());
        while (mvc.perform(post(NEXT_ORDER)).andReturn().getResponse().getStatus() == 200) {
            // 대기열이 빌 때까지
        }
    }

    private long lineWithShipments() {
        return jdbcTemplate.queryForObject("""
                select line_id from shipment
                 where status in ('TOTE_ASSIGNED', 'PACKING')
                 group by line_id order by count(*) desc, line_id asc limit 1
                """, Long.class);
    }

    private String nextTote(long lineId) throws Exception {
        MvcResult result = mvc.perform(post(NEXT_TOTE).param("lineId", String.valueOf(lineId)))
                .andReturn();
        if (result.getResponse().getStatus() == 204) {
            return null;
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString()
                .replaceAll(".*\"toteBarcode\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void 라인의_다음_토트를_알려준다() throws Exception {
        feedAllBatches();
        long lineId = lineWithShipments();

        mvc.perform(post(NEXT_TOTE).param("lineId", String.valueOf(lineId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toteBarcode").isNotEmpty())
                .andExpect(jsonPath("$.shipmentId").isNumber())
                .andExpect(jsonPath("$.receiptNo").isNotEmpty())
                .andExpect(jsonPath("$.remaining").isNumber());
    }

    @Test
    void 같은_토트를_두_번_내주지_않는다() throws Exception {
        feedAllBatches();
        long lineId = lineWithShipments();

        List<String> served = new ArrayList<>();
        String barcode;
        while ((barcode = nextTote(lineId)) != null) {
            served.add(barcode);
        }

        assertThat(served).isNotEmpty();
        assertThat(served).doesNotHaveDuplicates();
    }

    @Test
    void 내줄_토트가_없으면_내용_없음을_돌려준다() throws Exception {
        feedAllBatches();
        long lineId = lineWithShipments();
        while (nextTote(lineId) != null) {
            // 다 내줄 때까지
        }

        mvc.perform(post(NEXT_TOTE).param("lineId", String.valueOf(lineId)))
                .andExpect(status().isNoContent());
    }

    @Test
    void 포장할_것이_없는_라인은_내용_없음을_돌려준다() throws Exception {
        mvc.perform(post(RESET)).andExpect(status().isOk());
        Long emptyLine = jdbcTemplate.queryForObject(
                "select id from line order by id desc limit 1", Long.class);

        mvc.perform(post(NEXT_TOTE).param("lineId", String.valueOf(emptyLine)))
                .andExpect(status().isNoContent());
    }

    @Test
    void 남은_수는_내줄_때마다_줄어든다() throws Exception {
        feedAllBatches();
        long lineId = lineWithShipments();

        int first = remaining(lineId);
        int second = remaining(lineId);

        assertThat(second).isEqualTo(first - 1);
    }

    private int remaining(long lineId) throws Exception {
        String body = mvc.perform(post(NEXT_TOTE).param("lineId", String.valueOf(lineId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(body.replaceAll(".*\"remaining\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
