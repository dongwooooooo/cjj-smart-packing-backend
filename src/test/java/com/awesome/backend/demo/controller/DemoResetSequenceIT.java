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
 * 리셋을 이어서 두 번 누를 때와, 도중에 실패할 때 (명세 §4).
 *
 * <p>이 클래스는 테스트 트랜잭션을 쓰지 않는다. 리셋이 자기 트랜잭션을 커밋·롤백하는
 * 걸 봐야 하는데, 테스트가 트랜잭션을 들고 있으면 서비스가 거기 참여해 실제로
 * 되돌아가지 않는다. 남는 데이터는 매 테스트 뒤에 직접 지운다.
 */
@SpringBootTest
@Testcontainers
class DemoResetSequenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
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

    private void reset() throws Exception {
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    private void importFirstBatch() throws Exception {
        String batch = queueRepository.findFirstByReleasedAtIsNullOrderBySeqAsc()
                .orElseThrow().batchJson();
        mvc.perform(post(IMPORT).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk());
    }

    @Test
    void 두_번째_리셋은_첫_시연의_잔여물을_지운다() throws Exception {
        reset();
        importFirstBatch();

        assertThat(orderRepository.count()).isPositive();
        assertThat(count("select count(*) from shipment")).isPositive();
        assertThat(count("select count(*) from tote where status <> 'IDLE'")).isPositive();

        reset();

        assertThat(orderRepository.count()).isZero();
        assertThat(count("select count(*) from shipment")).isZero();
        assertThat(count("select count(*) from shipment_item")).isZero();
        assertThat(count("select count(*) from order_item")).isZero();
        assertThat(count("select count(*) from tote_assignment")).isZero();
        assertThat(count("select count(*) from tote where status <> 'IDLE'")).isZero();
        assertThat(queueRepository.countByReleasedAtIsNull()).isEqualTo(queueRepository.count());
    }

    @Test
    void 리셋을_두_번_눌러도_같은_상태가_된다() throws Exception {
        reset();
        String first = statusSnapshot();

        reset();

        assertThat(statusSnapshot()).isEqualTo(first);
    }

    @Test
    void 파일이_잘못됐으면_지우기_전에_멈춘다() throws Exception {
        reset();
        importFirstBatch();
        long ordersBefore = orderRepository.count();

        doThrow(new DemoDataException("orders.json: 일부러 낸 오류"))
                .when(loader).loadOrderBatches(any());

        mvc.perform(post(RESET)).andExpect(status().isInternalServerError());

        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(queueRepository.count()).isEqualTo(batchesInFile());
    }

    @Test
    void 지운_뒤_실패하면_지운_것까지_되돌린다() throws Exception {
        reset();
        importFirstBatch();

        long ordersBefore = orderRepository.count();
        int busyTotesBefore = count("select count(*) from tote where status <> 'IDLE'");
        assertThat(ordersBefore).isPositive();
        assertThat(busyTotesBefore).isPositive();

        // 삭제와 장비 복원이 끝난 뒤에 터지는 지점
        doThrow(new IllegalStateException("일부러 낸 오류"))
                .when(provisioner).provision(any());

        mvc.perform(post(RESET)).andExpect(status().isInternalServerError());

        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(count("select count(*) from tote where status <> 'IDLE'"))
                .isEqualTo(busyTotesBefore);
    }

    /** 상태를 비교 가능한 문자열로 — 두 번 리셋한 결과가 같은지 보는 용도. */
    private String statusSnapshot() {
        return jdbcTemplate.queryForObject("""
                select (select count(*) from orders) || '/' ||
                       (select count(*) from shipment) || '/' ||
                       (select count(*) from demo_order_queue where released_at is null) || '/' ||
                       (select count(*) from tote where status = 'IDLE') || '/' ||
                       (select count(*) from inventory_tx) || '/' ||
                       (select coalesce(sum(stock_qty), 0) from product) || '/' ||
                       (select coalesce(sum(stock_qty), 0) from box_type)
                """, String.class);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    /** 배치 수는 demo/data/orders.json 이 정한다. */
    private static long batchesInFile() {
        try {
            String json = java.nio.file.Files.readString(
                    java.nio.file.Path.of("demo/data/orders.json"));
            return java.util.regex.Pattern.compile("\"batchId\"").matcher(json).results().count();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("orders.json 을 읽지 못했다", e);
        }
    }
}
