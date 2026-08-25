package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.demo.service.DemoDataException;
import com.awesome.backend.demo.service.DemoDataLoader;
import com.awesome.backend.demo.service.DemoProductProvisioner;
import com.awesome.backend.orders.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 런을 이어서 두 번 돌릴 때와, 도중에 실패할 때 (명세 §4).
 *
 * <p>이 클래스는 테스트 트랜잭션을 쓰지 않는다. 런 시작이 자기 트랜잭션을 커밋·롤백하는
 * 걸 봐야 하는데, 테스트가 트랜잭션을 들고 있으면 서비스가 거기 참여해 실제로
 * 되돌아가지 않는다. 남는 데이터는 매 테스트 뒤에 직접 지운다.
 */
@SpringBootTest
@Testcontainers
class DemoRunSequenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RUNS = "/api/v1/admin/demo/runs";
    private static final String IMPORT = "/api/v1/admin/orders/import";

    @Autowired WebApplicationContext context;
    @Autowired DemoOrderQueueRepository queueRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoSpyBean DemoDataLoader loader;
    @MockitoSpyBean DemoProductProvisioner provisioner;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from demo_order_queue");
        jdbcTemplate.update("delete from inventory_tx");
        jdbcTemplate.update("delete from measurement_image");
        jdbcTemplate.update("delete from measurement_session");
        jdbcTemplate.update("delete from demo_product");
        jdbcTemplate.update("update tote set status = 'IDLE'");
        jdbcTemplate.update("update box_type set stock_qty = 100");
        jdbcTemplate.update("""
                update product set width_cm = null, length_cm = null, height_cm = null,
                                   dim_status = 'NONE', dim_method = null, stock_qty = 0
                """);
    }

    private String startRun() throws Exception {
        String body = mvc.perform(post(RUNS)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"runId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void 두_번째_런이_첫_런의_진행분을_종결한다() throws Exception {
        String first = startRun();
        String batch = queueRepository.findFirstByRunIdAndReleasedAtIsNullOrderBySeqAsc(first)
                .orElseThrow().batchJson();
        mvc.perform(post(IMPORT).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk());

        // 첫 런의 배치가 배송단위·토트를 잡은 상태
        assertThat(count("select count(*) from shipment where status = 'TOTE_ASSIGNED'")).isPositive();

        startRun();

        assertThat(count("select count(*) from shipment where status = 'TOTE_ASSIGNED'")).isZero();
        assertThat(count("select count(*) from shipment where status <> 'LOADED'")).isZero();
        assertThat(count("select count(*) from orders where status <> 'LOADED'")).isZero();
        assertThat(count("select count(*) from tote where status <> 'IDLE'")).isZero();
        assertThat(count("select count(*) from tote_assignment where released_at is null")).isZero();
        // 지운 게 아니라 종결만 했다
        assertThat(orderRepository.count()).isPositive();
    }

    @Test
    void 파일이_잘못됐으면_상태를_건드리기_전에_멈춘다() throws Exception {
        String first = startRun();
        int queuedBefore = queueRepository.countByRunIdAndReleasedAtIsNull(first);
        int stockBefore = stockOf("8801234500042");

        doThrow(new DemoDataException("orders.json: 일부러 낸 오류"))
                .when(loader).loadOrderBatches(any());

        mvc.perform(post(RUNS)).andExpect(status().isInternalServerError());

        assertThat(queueRepository.countByRunIdAndReleasedAtIsNull(first)).isEqualTo(queuedBefore);
        assertThat(stockOf("8801234500042")).isEqualTo(stockBefore);
        assertThat(count("select count(*) from demo_order_queue")).isEqualTo(queuedBefore);
    }

    @Test
    void 상태를_바꾼_뒤_실패하면_바꾼_것까지_되돌린다() throws Exception {
        String first = startRun();
        String batch = queueRepository.findFirstByRunIdAndReleasedAtIsNullOrderBySeqAsc(first)
                .orElseThrow().batchJson();
        mvc.perform(post(IMPORT).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk());
        jdbcTemplate.update("update box_type set stock_qty = 7");

        int assignedBefore = count("select count(*) from shipment where status = 'TOTE_ASSIGNED'");
        int busyTotesBefore = count("select count(*) from tote where status <> 'IDLE'");
        assertThat(assignedBefore).isPositive();
        assertThat(busyTotesBefore).isPositive();

        // 이전 런 종결과 박스 재고 복원이 끝난 뒤에 터지는 지점
        doThrow(new IllegalStateException("일부러 낸 오류"))
                .when(provisioner).provision(any());

        mvc.perform(post(RUNS)).andExpect(status().isInternalServerError());

        assertThat(count("select count(*) from shipment where status = 'TOTE_ASSIGNED'"))
                .isEqualTo(assignedBefore);
        assertThat(count("select count(*) from tote where status <> 'IDLE'"))
                .isEqualTo(busyTotesBefore);
        assertThat(count("select count(*) from tote_assignment where released_at is null"))
                .isEqualTo(busyTotesBefore);
        assertThat(count("select count(*) from box_type where stock_qty = 7")).isPositive();
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private int stockOf(String gtin) {
        return jdbcTemplate.queryForObject(
                "select stock_qty from product where gtin = ?", Integer.class, gtin);
    }
}
