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
import com.awesome.backend.support.StockTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
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
/*
 * 미리 포장해 두는 몫을 끈다. 이 테스트가 보는 것은 리셋이 처음 상태를 만들어 내는가라,
 * 포장이 재고와 박스를 쓰기 시작하면 무엇이 리셋의 결과인지 가려진다.
 * 미리 포장하는 동작은 DemoPrepackIT 가 본다.
 */
@SpringBootTest(properties = {
        "demo.prepacked-shipments=0",
        // 첫 리셋 뒤 집계가 방금 쓴 원장을 바로 접어야 두 번째 리셋의 옛 스냅샷 회귀를 재현할 수 있다.
        "inventory.collector.settle-seconds=0"})
@Testcontainers
@Transactional
@Import(StockTestSupport.class)
class DemoResetIT {

    /** 배치 수는 파일이 정한다 — 시연 구성이 바뀌어도 테스트가 따라 깨지지 않게 한다. */
    private static final int BATCHES = (int) batchesInFile();

    /**
     * 토트 수는 DB 에서 센다 — seed 가 늘어날 때마다 이 숫자를 고치지 않도록. 접수가 배송단위
     * 마다 토트를 하나씩 잡으므로, 대기 중인 토트는 전체에서 지금 잡힌 만큼을 뺀 값이다.
     */
    private int totalTotes() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from tote", Integer.class);
        return count == null ? 0 : count;
    }

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
    @Autowired com.awesome.backend.demo.service.DemoDataProperties demoProperties;
    @Autowired StockTestSupport stock;
    @Autowired com.awesome.backend.inventory.service.StockBalanceCollector stockBalanceCollector;

    private MockMvc mvc;

    /** 리셋이 미리 투입한 묶음 수 — 설정값과 파일의 묶음 수 중 작은 쪽이다. */
    private int prereleased() {
        return Math.min(Math.max(demoProperties.prereleasedBatches(), 0), BATCHES);
    }

    /** 지금 토트를 잡고 있는 배송단위 수. 미리 투입한 주문이 그만큼 토트를 가져간다. */
    private int activeAssignments() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from tote_assignment where released_at is null", Integer.class);
        return count == null ? 0 : count;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private void reset() throws Exception {
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    /** 기대 수치는 products.json 에서 읽는다 — 시연 상품이 바뀔 때마다 테스트를 고치지 않도록. */
    private static long countInFile(String pool) {
        try {
            String json = java.nio.file.Files.readString(
                    java.nio.file.Path.of("demo/data/products.json"));
            return java.util.regex.Pattern.compile("\"pool\"\\s*:\\s*\"" + pool + "\"")
                    .matcher(json).results().count();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("products.json 을 읽지 못했다", e);
        }
    }

    /** demo/data/orders.json 의 배치 수 — batchId 항목을 센다. */
    private static long batchesInFile() {
        try {
            String json = java.nio.file.Files.readString(
                    java.nio.file.Path.of("demo/data/orders.json"));
            return java.util.regex.Pattern.compile("\"batchId\"").matcher(json).results().count();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("orders.json 을 읽지 못했다", e);
        }
    }

    private static long totalInFile() {
        return countInFile("INBOUND") + countInFile("OUTBOUND");
    }

    @Test
    void 목록에서_빠진_상품은_리셋이_데모에서_빼고_재고도_되돌린다() throws Exception {
        // 지난 런의 잔여를 흉내낸다 — products.json 에 없는 상품이 데모 풀에 남아 있는 상태.
        // 그대로 두면 화면 상품 수가 부풀고, 사진이 없어 촬영이 NO_IMAGES 로 실패한다.
        String dropped = "8809999999999";
        jdbcTemplate.update("""
                insert into korean_net_master (gtin, product_name, medium_category_code, batch_id, imported_at)
                values (?, '지난 런 잔여 상품', 'C1010', 'TEST', now())
                on conflict (gtin) do nothing""", dropped);
        jdbcTemplate.update("""
                insert into product (gtin, name, medium_category_code, image_url, source, dim_status)
                values (?, '지난 런 잔여 상품', 'C1010', 'x', 'MASTER', 'NONE')
                on conflict (gtin) do nothing""", dropped);
        stock.set(dropped, 40);
        jdbcTemplate.update("""
                insert into demo_product (gtin, pool, gt_width_cm, gt_length_cm, gt_height_cm, image_dir)
                values (?, 'INBOUND', 7.0, 7.0, 23.0, 'images/' || ?)
                on conflict (gtin) do nothing""", dropped, dropped);

        reset();

        assertThat(demoProductRepository.findById(dropped)).isEmpty();
        assertThat(stock.onHand(dropped)).isZero();
        // 파일에 있는 상품은 그대로 남는다
        assertThat(demoProductRepository.count()).isEqualTo(totalInFile());
    }

    @Test
    void 리셋하면_요약을_돌려준다() throws Exception {
        mvc.perform(post(RESET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.inbound").value((int) countInFile("INBOUND")))
                .andExpect(jsonPath("$.products.outbound").value((int) countInFile("OUTBOUND")))
                .andExpect(jsonPath("$.queuedBatches").value(BATCHES))
                .andExpect(jsonPath("$.totes.idle").value(totalTotes() - activeAssignments()))
                .andExpect(jsonPath("$.totes.assigned").value(activeAssignments()))
                .andExpect(jsonPath("$.boxTypes.stockQty").value(100))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString(
                        "입고 풀 " + countInFile("INBOUND"))));
    }

    @Test
    void 입고_풀_상품은_치수가_비고_재고가_0이다() throws Exception {
        reset();

        for (DemoProduct demo : demoProductRepository.findByPool(DemoProduct.Pool.INBOUND)) {
            Product product = productRepository.findByGtin(demo.gtin()).orElseThrow();
            assertThat(product.dimStatus()).isEqualTo(Product.DIM_STATUS_NONE);
            assertThat(product.widthCm()).isNull();
            assertThat(stock.onHand(product.gtin())).isZero();
            assertThat(demo.gtWidthCm()).isNotNull();
            assertThat(demo.imageDir()).isNotBlank();
        }
    }

    @Test
    void 출고_풀_상품은_치수가_확정되고_재고가_채워진다() throws Exception {
        reset();

        List<DemoProduct> outbound = demoProductRepository.findByPool(DemoProduct.Pool.OUTBOUND);
        assertThat(outbound).hasSize((int) countInFile("OUTBOUND"));
        for (DemoProduct demo : outbound) {
            Product product = productRepository.findByGtin(demo.gtin()).orElseThrow();
            assertThat(product.dimStatus()).isEqualTo(Product.DIM_STATUS_CONFIRMED);
            assertThat(product.widthCm()).isNotNull();
            assertThat(stock.onHand(product.gtin())).isPositive();
        }
    }

    @Test
    void 재고를_목표값에_맞춘_기록이_원장에_남는다() throws Exception {
        reset();

        Integer ledgerRows = jdbcTemplate.queryForObject(
                "select count(*) from inventory_tx", Integer.class);
        assertThat(ledgerRows).isEqualTo((int) countInFile("OUTBOUND"));
    }

    @Test
    void 대기열은_파일_순서대로_쌓이고_앞쪽은_미리_투입된다() throws Exception {
        reset();

        List<DemoOrderQueue> queued = queueRepository.findAllByOrderBySeqAsc();
        assertThat(queued).hasSize(BATCHES);
        assertThat(queued.getFirst().seq()).isEqualTo(1);
        assertThat(queued.getFirst().batchJson()).contains("R-DEMO-0001");

        // 시연을 시작하면 라인마다 포장할 배송단위가 이미 놓여 있어야 한다. 앞쪽 묶음은
        // 리셋이 미리 투입하고, 남긴 묶음은 시연 도중 화면의 Load 로 넣는다.
        assertThat(queued.stream().filter(batch -> batch.releasedAt() != null))
                .hasSize(prereleased());
        assertThat(queueRepository.countByReleasedAtIsNull()).isEqualTo(BATCHES - prereleased());
        assertThat(queued.subList(0, prereleased()))
                .allSatisfy(batch -> assertThat(batch.releasedAt()).isNotNull());
    }

    @Test
    void 박스_재고와_토트를_처음_상태로_되돌린다() throws Exception {
        jdbcTemplate.update("update box_type set stock_qty = 3");
        jdbcTemplate.update("update tote set status = 'ASSIGNED'");

        reset();

        assertThat(boxTypeRepository.findAll()).allSatisfy(
                box -> assertThat(box.stockQty()).isEqualTo(100));
        assertThat(toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE))
                .hasSize(totalTotes() - activeAssignments());
    }

    @Test
    void 남아_있던_측정_세션을_지운다() throws Exception {
        reset();
        Long productId = productRepository.findByGtin(demoProductRepository.findByPool(DemoProduct.Pool.INBOUND).getFirst().gtin()).orElseThrow().id();
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
                .andExpect(jsonPath("$.products[?(@.pool == 'INBOUND')].items.length()").value((int) countInFile("INBOUND")))
                .andExpect(jsonPath("$.products[?(@.pool == 'OUTBOUND')].items.length()")
                        .value((int) countInFile("OUTBOUND")))
                .andExpect(jsonPath("$.batches.length()").value(BATCHES))
                .andExpect(jsonPath("$.batches[0].seq").value(1))
                .andExpect(jsonPath("$.batches[0].orderCount").value(1))
                .andExpect(jsonPath("$.batches[0].released").value(true))
                .andExpect(jsonPath("$.totes.idle").value(totalTotes() - activeAssignments()))
                .andExpect(jsonPath("$.boxTypes.stockQty").value(100))
                .andExpect(jsonPath("$.progress.orders").value(prereleased()))
                .andExpect(jsonPath("$.progress.shipments").value(activeAssignments()))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString(
                        "대기 배치 " + BATCHES + "개 중 " + prereleased() + "개 투입")));
    }

    @Test
    void 남아_있던_측정_세션은_이미지까지_함께_지운다() throws Exception {
        reset();
        Long productId = productRepository.findByGtin(demoProductRepository.findByPool(DemoProduct.Pool.INBOUND).getFirst().gtin()).orElseThrow().id();
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

    @Test
    void 리셋_뒤_실재고는_시연_명세_수량과_같고_스냅샷은_원장과_일치한다() throws Exception {
        // 리셋 직후에는 stock_balance 에 행이 없다(리셋이 지운다) — 실서비스에서는 이 행을
        // StockBalanceCollector 가 리셋 뒤 몇 초 안에 만든다. 테스트는 클래스 레벨 @Transactional 이라
        // 집계를 테스트 트랜잭션 안에서 직접 불러 그 상태를 흉내낸다.
        mvc.perform(post(RESET)).andExpect(status().isOk());
        stockBalanceCollector.collectOnce();

        // 진짜 문제는 두 번째 리셋(시연을 다시 준비하려고 또 누르는 경우)부터 드러난다.
        // clearDemoData 가 원장만 지우고 스냅샷을 그대로 두면, alignStock 이 옛 스냅샷
        // 기준으로 delta 를 0으로 계산해 넘어가고, 스냅샷의 last_tx_id 는 방금 지워진
        // 원장 행을 가리킨 채 남는다.
        mvc.perform(post(RESET)).andExpect(status().isOk());
        stockBalanceCollector.collectOnce();

        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_balance", Long.class))
                .isEqualTo(jdbcTemplate.queryForObject("select count(*) from product", Long.class));
        Long mismatches = jdbcTemplate.queryForObject("""
                select count(*) from stock_balance b
                 where b.qty + coalesce((select sum(t.qty_delta) from inventory_tx t
                                          where t.product_id = b.product_id and t.id > b.last_tx_id), 0)
                    <> coalesce((select sum(t.qty_delta) from inventory_tx t where t.product_id = b.product_id), 0)
                """, Long.class);
        assertThat(mismatches).isZero();
    }
}
