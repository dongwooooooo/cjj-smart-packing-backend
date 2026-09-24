package com.awesome.backend.inbound.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inbound.entity.MeasurementSession;
import com.awesome.backend.inbound.entity.MeasurementStatus;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.support.StockTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 측정 확정 (02 §1-4).
 *
 * <p>확정 대상 세션은 1-3 을 호출해 만든다 — 세션 생성 경로가 그것뿐이라 엔티티를 직접
 * 만들면 실제 흐름과 어긋난다. mock confidence 는 게이트 임계값 위로 고정한다.
 */
@SpringBootTest(properties = {
        "inference.mock.min-confidence=0.95",
        "inference.mock.max-confidence=0.99"
})
@Testcontainers
@Transactional
@Import(StockTestSupport.class)
class MeasurementConfirmIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String MEASURE_PATH = "/api/v1/inbound/measurements";

    /** V2 seed 의 시연 상품 — 분류 C1010, 사전 등록 무게 1.080kg (D-10). */
    private static final String JUICE = "8801234500011";

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired MeasurementSessionRepository sessionRepository;
    @Autowired StockTestSupport stock;
    @PersistenceContext EntityManager em;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Long juiceId() {
        return productRepository.findByGtin(JUICE).map(Product::id).orElseThrow();
    }

    /** 1-3 을 호출해 확정 대상 세션을 만든다. */
    private Long openSession(Long productId) throws Exception {
        mvc.perform(post(MEASURE_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "productId": %d }""".formatted(productId)))
                .andExpect(status().isOk());

        return sessionRepository.findByProductIdAndStatusIn(productId,
                List.of(MeasurementStatus.INFERRED, MeasurementStatus.MEASURE_FAILED)).getFirst().getId();
    }

    private String confirmPath(Long sessionId) {
        return MEASURE_PATH + "/" + sessionId + "/confirm";
    }

    private static final String HANDLING =
            """
            "handling": { "refrigerate": true, "fragile": true, "irregular": false }""";

    private String approveBody() {
        return "{ \"method\": \"APPROVE\", " + HANDLING + " }";
    }

    @Test
    void 승인하면_추론값이_상품에_확정된다() throws Exception {
        Long productId = juiceId();
        Long sessionId = openSession(productId);
        MeasurementSession session = sessionRepository.findById(sessionId).orElseThrow();
        BigDecimal inferredWidth = session.getInferredWidthCm();

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.dimStatus").value("CONFIRMED"))
                // APPROVE 는 추론값 승인이라 dimMethod 가 INFERRED 다 (method 이름과 다르다)
                .andExpect(jsonPath("$.dimMethod").value("INFERRED"));

        em.flush();
        em.clear();
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.widthCm()).isEqualByComparingTo(inferredWidth);
        assertThat(product.hasConfirmedDimensions()).isTrue();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(MeasurementStatus.CONFIRMED);
    }

    @Test
    void 무게를_생략하면_세션의_저울값을_쓴다() throws Exception {
        Long productId = juiceId();
        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isOk());

        em.flush();
        em.clear();
        assertThat(productRepository.findById(productId).orElseThrow().weightKg())
                .isEqualByComparingTo(new BigDecimal("1.080"));
    }

    @Test
    void 무게를_주면_세션_저울값보다_우선한다() throws Exception {
        Long productId = juiceId();
        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"method\": \"APPROVE\", \"weightKg\": 2.500, " + HANDLING + " }"))
                .andExpect(status().isOk());

        em.flush();
        em.clear();
        assertThat(productRepository.findById(productId).orElseThrow().weightKg())
                .isEqualByComparingTo(new BigDecimal("2.500"));
    }

    @Test
    void 수기_확정은_요청_치수를_쓰고_dimMethod가_MANUAL이다() throws Exception {
        Long productId = juiceId();
        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "MANUAL",
                                  "dims": { "widthCm": 6.5, "lengthCm": 6.5, "heightCm": 21.0 },
                                  "weightKg": 0.520,
                                  %s }""".formatted(HANDLING)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimMethod").value("MANUAL"));

        em.flush();
        em.clear();
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.heightCm()).isEqualByComparingTo(new BigDecimal("21.0"));
        assertThat(product.weightKg()).isEqualByComparingTo(new BigDecimal("0.520"));
    }

    @Test
    void 수기_치수도_축_규약대로_정렬해_저장한다() throws Exception {
        // D-18: 가로 < 세로로 들어와도 서버가 스왑한다. 높이는 건드리지 않는다.
        Long productId = juiceId();
        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "MANUAL",
                                  "dims": { "widthCm": 5.0, "lengthCm": 12.0, "heightCm": 30.0 },
                                  "weightKg": 0.5,
                                  %s }""".formatted(HANDLING)))
                .andExpect(status().isOk());

        em.flush();
        em.clear();
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.widthCm()).isEqualByComparingTo(new BigDecimal("12.0"));
        assertThat(product.lengthCm()).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(product.heightCm()).isEqualByComparingTo(new BigDecimal("30.0"));
    }

    @Test
    void 취급속성이_상품에_기록된다() throws Exception {
        Long productId = juiceId();
        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "APPROVE",
                                  "handling": { "refrigerate": false, "fragile": true, "irregular": true } }"""))
                .andExpect(status().isOk());

        em.flush();
        em.clear();
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.refrigerate()).isFalse();
        assertThat(product.fragile()).isTrue();
        assertThat(product.irregular()).isTrue();
    }

    @Test
    void 확정은_재고를_건드리지_않는다() throws Exception {
        // D-09: 재고 증가는 1-5 stock-in 한 곳뿐이다
        Long productId = juiceId();
        int before = stock.onHandByProductId(productId);
        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isOk());

        em.flush();
        em.clear();
        assertThat(stock.onHandByProductId(productId)).isEqualTo(before);
    }

    @Test
    void 없는_세션이면_404_SESSION_NOT_FOUND() throws Exception {
        mvc.perform(post(confirmPath(999_999L)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail.sessionId").value(999_999));
    }

    @Test
    void 이미_확정한_세션은_409_SESSION_ALREADY_CONFIRMED() throws Exception {
        Long sessionId = openSession(juiceId());

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isOk());

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ALREADY_CONFIRMED"));
    }

    @Test
    void 재촬영으로_폐기된_세션도_409() throws Exception {
        // 폐기 세션을 뒤늦게 확정하면 최신 촬영 결과를 덮어쓴다
        Long productId = juiceId();
        Long oldSessionId = openSession(productId);
        openSession(productId);

        mvc.perform(post(confirmPath(oldSessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ALREADY_CONFIRMED"))
                .andExpect(jsonPath("$.detail.status").value("DISCARDED"));
    }

    @Test
    void 게이트_미통과_세션은_승인할_수_없다() throws Exception {
        Long productId = juiceId();
        Long sessionId = openSession(productId);
        // mock 은 게이트를 통과시키므로 여기서 직접 미통과 상태로 만든다
        em.createQuery("update MeasurementSession s set s.gatePassed = false where s.id = :id")
                .setParameter("id", sessionId)
                .executeUpdate();
        em.clear();

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATE_NOT_PASSED"));
    }

    @Test
    void 게이트_미통과여도_수기_확정은_된다() throws Exception {
        // 게이트는 치수 추론에만 걸린다 — 해제 경로가 재촬영 또는 수기 확정이다
        Long productId = juiceId();
        Long sessionId = openSession(productId);
        em.createQuery("update MeasurementSession s set s.gatePassed = false where s.id = :id")
                .setParameter("id", sessionId)
                .executeUpdate();
        em.clear();

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "MANUAL",
                                  "dims": { "widthCm": 6.5, "lengthCm": 6.5, "heightCm": 21.0 },
                                  %s }""".formatted(HANDLING)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimMethod").value("MANUAL"));
    }

    @Test
    void 수기_확정에_dims가_없으면_400() throws Exception {
        Long sessionId = openSession(juiceId());

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"method\": \"MANUAL\", \"weightKg\": 0.5, " + HANDLING + " }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 세션_저울값도_없고_weightKg도_없으면_400() throws Exception {
        Long productId = juiceId();
        // 저울값의 출처인 사전 등록 무게를 비운 뒤 촬영해야 세션 저울값이 null 이 된다
        em.createQuery("update Product p set p.weightKg = null where p.id = :id")
                .setParameter("id", productId)
                .executeUpdate();
        em.clear();

        Long sessionId = openSession(productId);

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void handling이_없으면_400() throws Exception {
        Long sessionId = openSession(juiceId());

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"method\": \"APPROVE\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 확정_후_재스캔하면_REGISTERED로_판정된다() throws Exception {
        // 1-1 의 3분기가 dim_status 를 보므로, 확정이 스캔 판정까지 바꿔야 흐름이 이어진다
        Long sessionId = openSession(juiceId());

        mvc.perform(post(confirmPath(sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody()))
                .andExpect(status().isOk());

        em.flush();
        em.clear();

        mvc.perform(post("/api/v1/inbound/scans").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "barcode": "%s" }""".formatted(JUICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgment").value("REGISTERED"))
                .andExpect(jsonPath("$.product.dimStatus").value("CONFIRMED"));
    }
}
