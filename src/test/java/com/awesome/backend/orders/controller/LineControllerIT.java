package com.awesome.backend.orders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.repository.LineRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * GET /api/v1/lines — 포장 화면의 라인 선택 칸이 쓰는 목록.
 */
@SpringBootTest
@Testcontainers
@Transactional
class LineControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String LINES = "/api/v1/lines";

    @Autowired WebApplicationContext context;
    @Autowired LineRepository lineRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 라인을_이름과_담당지역까지_돌려준다() throws Exception {
        List<Line> seeded = lineRepository.findAllByOrderByIdAsc();
        assertThat(seeded).isNotEmpty();
        Line first = seeded.getFirst();

        mvc.perform(get(LINES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(seeded.size()))
                .andExpect(jsonPath("$.lines[0].lineId").value(first.id()))
                .andExpect(jsonPath("$.lines[0].name").value(first.name()))
                .andExpect(jsonPath("$.lines[0].regionCode").value(first.regionCode()))
                .andExpect(jsonPath("$.lines[0].status").value(first.status().name()));
    }

    @Test
    void 화면이_늘_같은_순서로_보이도록_식별자_오름차순이다() throws Exception {
        List<Long> ids = lineRepository.findAllByOrderByIdAsc().stream().map(Line::id).toList();

        String body = mvc.perform(get(LINES)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> returned = com.jayway.jsonpath.JsonPath.read(body, "$.lines[*].lineId");
        assertThat(returned.stream().map(Integer::longValue).toList()).isEqualTo(ids);
        assertThat(returned).isSorted();
    }

    @Test
    void 멈춘_라인도_목록에_남는다() throws Exception {
        // 라인 하나가 멈춰도 화면의 라인 칸은 자리를 지켜야 한다. 고를 수 없게 보이는 것은
        // 화면이 status 를 보고 판단한다.
        //
        // 라인을 저장소로 먼저 읽지 않는다 — 읽어 두면 같은 트랜잭션 안에서 그 엔티티가 캐시에
        // 남아, 아래 SQL 로 상태를 바꿔도 조회가 바뀌기 전 값을 돌려준다.
        Long lineId = jdbcTemplate.queryForObject("select min(id) from line", Long.class);
        jdbcTemplate.update("update line set status = 'PAUSED' where id = ?", lineId);

        mvc.perform(get(LINES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].lineId").value(lineId))
                .andExpect(jsonPath("$.lines[0].status").value("PAUSED"));
    }

    @Test
    void 라인이_하나도_없으면_빈_목록이다() throws Exception {
        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from line");

        mvc.perform(get(LINES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(0));
    }
}
