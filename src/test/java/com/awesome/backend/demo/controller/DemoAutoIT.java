package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.demo.service.DemoAutoFeeder;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.orders.service.OrderImportService;
import com.awesome.backend.support.StockTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 자동 투입 (명세 §5). 스케줄러를 1초 간격으로 실제로 돌려 확인한다.
 *
 * <p>테스트 트랜잭션을 쓰지 않는다. 스케줄러가 다른 스레드에서 자기 트랜잭션으로 돌기
 * 때문에, 테스트가 트랜잭션을 들고 있으면 투입 결과가 보이지 않는다.
 */
/*
 * 리셋이 미리 투입하는 몫을 끈다. 이 테스트가 보는 것은 주문을 하나씩 넣는 통로 자체라,
 * 대기열이 비어 있는 상태에서 시작해야 몇 번째 묶음이 나가는지 확인할 수 있다.
 * 미리 투입하는 동작은 DemoResetIT 가 본다.
 */
@SpringBootTest(properties = "demo.prereleased-batches=0")
@Testcontainers
@Import(StockTestSupport.class)
class DemoAutoIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
    private static final String STATUS = "/api/v1/admin/demo/status";
    private static final String AUTO = "/api/v1/admin/demo/orders/auto";

    @Autowired WebApplicationContext context;
    @Autowired DemoOrderQueueRepository queueRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired DemoAutoFeeder autoFeeder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StockTestSupport stock;

    @MockitoSpyBean OrderImportService orderImportService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        autoFeeder.stop();
        jdbcTemplate.update("delete from tote_assignment");
        jdbcTemplate.update("delete from shipment_item");
        jdbcTemplate.update("delete from shipment");
        jdbcTemplate.update("delete from order_item");
        jdbcTemplate.update("delete from orders");
        jdbcTemplate.update("delete from demo_order_queue");
        jdbcTemplate.update("delete from inventory_tx");
        jdbcTemplate.update("delete from demo_product");
        jdbcTemplate.update("update tote set status = 'IDLE'");
        jdbcTemplate.update("update box_type set stock_qty = 100");
        jdbcTemplate.update("""
                update product set width_cm = null, length_cm = null, height_cm = null,
                                   dim_status = 'NONE', dim_method = null
                """);
        for (var product : productRepository.findAll()) {
            stock.set(product.gtin(), 0);
        }
    }

    @Test
    void 시작하면_간격마다_한_배치씩_들어간다() throws Exception {
        mvc.perform(post(AUTO).param("intervalSeconds", "1")).andExpect(status().isOk());

        int total = (int) queueRepository.count();

        await().atMost(Duration.ofSeconds(20))
                .until(() -> orderRepository.count() >= 2);

        // 배치가 여러 개라 다 빠지길 기다리지 않는다 — 간격마다 줄어드는 것만 본다
        assertThat(queueRepository.countByReleasedAtIsNull()).isLessThan(total);
    }

    @Test
    void 대기열이_비면_스스로_멈춘다() throws Exception {
        mvc.perform(post(AUTO).param("intervalSeconds", "1")).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(90))
                .until(() -> !autoFeeder.isRunning());

        assertThat(queueRepository.countByReleasedAtIsNull()).isZero();
        assertThat(orderRepository.count()).isPositive();
    }

    @Test
    void 이미_돌고_있으면_두_번째_시작을_거절한다() throws Exception {
        mvc.perform(post(AUTO).param("intervalSeconds", "600")).andExpect(status().isOk());

        mvc.perform(post(AUTO).param("intervalSeconds", "5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.intervalSeconds").value(600));
    }

    @Test
    void 간격이_범위를_벗어나면_거절한다() throws Exception {
        mvc.perform(post(AUTO).param("intervalSeconds", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(AUTO).param("intervalSeconds", "601"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 정지하면_더_들어가지_않는다() throws Exception {
        mvc.perform(post(AUTO).param("intervalSeconds", "600")).andExpect(status().isOk());
        mvc.perform(delete(AUTO)).andExpect(status().isOk());

        assertThat(autoFeeder.isRunning()).isFalse();
        mvc.perform(get(STATUS)).andExpect(jsonPath("$.auto.running").value(false));
    }

    @Test
    void 상태_조회에_자동_투입_여부가_보인다() throws Exception {
        mvc.perform(get(STATUS))
                .andExpect(jsonPath("$.auto.running").value(false))
                .andExpect(jsonPath("$.auto.intervalSeconds").doesNotExist());

        mvc.perform(post(AUTO).param("intervalSeconds", "600")).andExpect(status().isOk());

        mvc.perform(get(STATUS))
                .andExpect(jsonPath("$.auto.running").value(true))
                .andExpect(jsonPath("$.auto.intervalSeconds").value(600));
    }

    @Test
    void 투입이_실패하면_멈추고_배치는_대기열에_남는다() throws Exception {
        doThrow(new IllegalStateException("일부러 낸 오류"))
                .when(orderImportService).importOrders(any());

        mvc.perform(post(AUTO).param("intervalSeconds", "1")).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(20))
                .until(() -> !autoFeeder.isRunning());

        assertThat(queueRepository.countByReleasedAtIsNull()).isEqualTo(queueRepository.count());
        assertThat(orderRepository.count()).isZero();
    }
}
