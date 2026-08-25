package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.entity.DemoOrderQueue;
import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
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
 * 새 런 시작 (명세 §4). 서버를 띄운 뒤 이 API 한 번으로 시연 상태가 만들어진다.
 * 아무것도 지우지 않는다 — 이전 런은 종결 처리만 하고 이력으로 남는다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class DemoRunIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/admin/demo/runs";

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired DemoProductRepository demoProductRepository;
    @Autowired DemoOrderQueueRepository demoOrderQueueRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired BoxTypeRepository boxTypeRepository;
    @Autowired MeasurementSessionRepository measurementSessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String startRun() throws Exception {
        String body = mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"runId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void 런을_시작하면_요약을_돌려준다() throws Exception {
        mvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.products.inbound").value(3))
                .andExpect(jsonPath("$.products.outbound").value(3))
                .andExpect(jsonPath("$.queuedBatches").value(3))
                .andExpect(jsonPath("$.totes.idle").value(10))
                .andExpect(jsonPath("$.totes.assigned").value(0));
    }

    @Test
    void 입고_풀_상품은_치수가_비고_재고가_0이다() throws Exception {
        startRun();

        for (DemoProduct demo : demoProductRepository.findByPool(DemoProduct.Pool.INBOUND)) {
            Product product = productRepository.findByGtin(demo.gtin()).orElseThrow();
            assertThat(product.dimStatus()).isEqualTo(Product.DIM_STATUS_NONE);
            assertThat(product.widthCm()).isNull();
            assertThat(product.lengthCm()).isNull();
            assertThat(product.heightCm()).isNull();
            assertThat(product.stockQty()).isZero();
            assertThat(demo.gtWidthCm()).isNotNull();
            assertThat(demo.imageDir()).isNotBlank();
        }
    }

    @Test
    void 출고_풀_상품은_치수가_확정되고_재고가_채워진다() throws Exception {
        startRun();

        List<DemoProduct> outbound = demoProductRepository.findByPool(DemoProduct.Pool.OUTBOUND);
        assertThat(outbound).hasSize(3);
        for (DemoProduct demo : outbound) {
            Product product = productRepository.findByGtin(demo.gtin()).orElseThrow();
            assertThat(product.dimStatus()).isEqualTo(Product.DIM_STATUS_CONFIRMED);
            assertThat(product.widthCm()).isNotNull();
            assertThat(product.stockQty()).isPositive();
        }
    }

    @Test
    void 재고를_목표값에_맞춘_기록이_원장에_남는다() throws Exception {
        startRun();

        Integer adjustRows = jdbcTemplate.queryForObject(
                "select count(*) from inventory_tx where tx_type = 'ADJUST'", Integer.class);
        assertThat(adjustRows).isPositive();
    }

    @Test
    void 대기열_주문번호에_런_아이디가_붙는다() throws Exception {
        String runId = startRun();

        List<DemoOrderQueue> queued = demoOrderQueueRepository.findAll().stream()
                .filter(q -> q.runId().equals(runId))
                .sorted((a, b) -> Integer.compare(a.seq(), b.seq()))
                .toList();

        assertThat(queued).hasSize(3);
        assertThat(queued.getFirst().seq()).isEqualTo(1);
        assertThat(queued.getFirst().batchJson()).contains(runId + "-R-DEMO-0001");
        assertThat(queued.getFirst().releasedAt()).isNull();
        assertThat(demoOrderQueueRepository.countByRunIdAndReleasedAtIsNull(runId)).isEqualTo(3);
    }

    @Test
    void 박스_재고를_seed값으로_되돌린다() throws Exception {
        jdbcTemplate.update("update box_type set stock_qty = 3");

        mvc.perform(post(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.boxTypes.restoredTo").value(100));

        assertThat(boxTypeRepository.findAll()).allSatisfy(
                box -> assertThat(box.stockQty()).isEqualTo(100));
    }

    @Test
    void 토트를_전부_유휴로_되돌린다() throws Exception {
        jdbcTemplate.update("update tote set status = 'ASSIGNED'");

        startRun();

        assertThat(toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE)).hasSize(10);
    }

    @Test
    void 미확정_측정_세션은_폐기로_종결한다() throws Exception {
        startRun();
        Long productId = productRepository.findByGtin("8801234500028").orElseThrow().id();
        jdbcTemplate.update("""
                insert into measurement_session (product_id, status, gate_passed, created_at)
                values (?, 'INFERRED', false, now())
                """, productId);

        mvc.perform(post(PATH)).andExpect(status().isOk());

        // JPA로 확인한다 — 폐기 전이는 엔티티 변경이라 트랜잭션이 끝나야 테이블에 반영된다
        assertThat(measurementSessionRepository.findAll())
                .noneMatch(MeasurementSession::isOpen);
    }

    @Test
    void 런을_두_번_시작하면_대기열이_런별로_쌓인다() throws Exception {
        String first = startRun();
        String second = startRun();

        assertThat(second).isNotEqualTo(first);
        assertThat(demoOrderQueueRepository.countByRunIdAndReleasedAtIsNull(first)).isEqualTo(3);
        assertThat(demoOrderQueueRepository.countByRunIdAndReleasedAtIsNull(second)).isEqualTo(3);
    }
}
