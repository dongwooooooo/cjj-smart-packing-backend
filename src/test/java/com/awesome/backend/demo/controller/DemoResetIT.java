package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 시연 리셋과 상태 조회 (명세 §4). 한 번 눌러 시연 시작 상태를 만든다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class DemoResetIT {

    /** demo/data/orders.json 의 배치 수. 라인 대시보드를 채우려면 여러 개가 필요하다. */
    private static final int BATCHES = 18;

    /** V2 seed 10개 + V6 이 더한 30개. 접수가 주문마다 토트를 하나씩 잡는다. */
    private static final int TOTES = 40;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
    private static final String STATUS = "/api/v1/admin/demo/status";

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired DemoProductRepository demoProductRepository;
    @Autowired DemoOrderQueueRepository queueRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired BoxTypeRepository boxTypeRepository;
    @Autowired MeasurementSessionRepository measurementSessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private void reset() throws Exception {
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    @Test
    void 리셋하면_요약을_돌려준다() throws Exception {
        mvc.perform(post(RESET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.inbound").value(6))
                .andExpect(jsonPath("$.products.outbound").value(10))
                .andExpect(jsonPath("$.queuedBatches").value(BATCHES))
                .andExpect(jsonPath("$.totes.idle").value(TOTES))
                .andExpect(jsonPath("$.totes.assigned").value(0))
                .andExpect(jsonPath("$.boxTypes.stockQty").value(100))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("입고 풀 6")));
    }

    @Test
    void 입고_풀_상품은_치수가_비고_재고가_0이다() throws Exception {
        reset();

        for (DemoProduct demo : demoProductRepository.findByPool(DemoProduct.Pool.INBOUND)) {
            Product product = productRepository.findByGtin(demo.gtin()).orElseThrow();
            assertThat(product.dimStatus()).isEqualTo(Product.DIM_STATUS_NONE);
            assertThat(product.widthCm()).isNull();
            assertThat(product.stockQty()).isZero();
            assertThat(demo.gtWidthCm()).isNotNull();
            assertThat(demo.imageDir()).isNotBlank();
        }
    }

    @Test
    void 출고_풀_상품은_치수가_확정되고_재고가_채워진다() throws Exception {
        reset();

        List<DemoProduct> outbound = demoProductRepository.findByPool(DemoProduct.Pool.OUTBOUND);
        assertThat(outbound).hasSize(10);
        for (DemoProduct demo : outbound) {
            Product product = productRepository.findByGtin(demo.gtin()).orElseThrow();
            assertThat(product.dimStatus()).isEqualTo(Product.DIM_STATUS_CONFIRMED);
            assertThat(product.widthCm()).isNotNull();
            assertThat(product.stockQty()).isPositive();
        }
    }

    @Test
    void 재고를_목표값에_맞춘_기록이_원장에_남는다() throws Exception {
        reset();

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from inventory_tx", Integer.class);
        assertThat(ledgerRows).isEqualTo(10);
    }

    @Test
    void 대기열은_파일_순서대로_쌓이고_아직_투입_전이다() throws Exception {
        reset();

        List<DemoOrderQueue> queued = queueRepository.findAllByOrderBySeqAsc();
        assertThat(queued).hasSize(BATCHES);
        assertThat(queued.getFirst().seq()).isEqualTo(1);
        assertThat(queued.getFirst().batchJson()).contains("R-DEMO-0001");
        assertThat(queued).allSatisfy(batch -> assertThat(batch.releasedAt()).isNull());
        assertThat(queueRepository.countByReleasedAtIsNull()).isEqualTo(BATCHES);
    }

    @Test
    void 박스_재고와_토트를_처음_상태로_되돌린다() throws Exception {
        jdbcTemplate.update("update box_type set stock_qty = 3");
        jdbcTemplate.update("update tote set status = 'ASSIGNED'");

        reset();

        assertThat(boxTypeRepository.findAll()).allSatisfy(
                box -> assertThat(box.stockQty()).isEqualTo(100));
        assertThat(toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE)).hasSize(TOTES);
    }

    @Test
    void 남아_있던_측정_세션을_지운다() throws Exception {
        reset();
        Long productId = productRepository.findByGtin("8801234500028").orElseThrow().id();
        jdbcTemplate.update("""
                insert into measurement_session (product_id, status, gate_passed, created_at)
                values (?, 'INFERRED', false, now())
                """, productId);

        reset();

        assertThat(measurementSessionRepository.count()).isZero();
    }

    @Test
    void 상태_조회는_풀별_상품과_대기열을_보여준다() throws Exception {
        reset();

        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[?(@.pool == 'INBOUND')].items.length()").value(6))
                .andExpect(jsonPath("$.products[?(@.pool == 'OUTBOUND')].items.length()").value(10))
                .andExpect(jsonPath("$.batches.length()").value(BATCHES))
                .andExpect(jsonPath("$.batches[0].seq").value(1))
                .andExpect(jsonPath("$.batches[0].orderCount").value(1))
                .andExpect(jsonPath("$.batches[0].released").value(false))
                .andExpect(jsonPath("$.totes.idle").value(TOTES))
                .andExpect(jsonPath("$.boxTypes.stockQty").value(100))
                .andExpect(jsonPath("$.progress.orders").value(0))
                .andExpect(jsonPath("$.progress.shipments").value(0))
                .andExpect(jsonPath("$.summary").value(
                        org.hamcrest.Matchers.containsString("대기 배치 " + BATCHES + "개 중 0개 투입")));
    }

    @Test
    void 남아_있던_측정_세션은_이미지까지_함께_지운다() throws Exception {
        reset();
        Long productId = productRepository.findByGtin("8801234500028").orElseThrow().id();
        jdbcTemplate.update("""
                insert into measurement_session (id, product_id, status, gate_passed, created_at)
                values (9001, ?, 'INFERRED', false, now())
                """, productId);
        jdbcTemplate.update("""
                insert into measurement_image (session_id, camera_no, file_path, created_at)
                values (9001, 1, 'x.jpg', now())
                """);

        reset();

        assertThat(measurementSessionRepository.count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from measurement_image", Integer.class)).isZero();
    }

    @Test
    void 잘못된_측정_세션이_있어도_리셋은_통과한다() throws Exception {
        reset();

        assertThat(measurementSessionRepository.findAll())
                .noneMatch(MeasurementSession::isOpen);
    }
}
