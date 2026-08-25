package com.awesome.backend.inbound.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inbound.entity.MeasurementStatus;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 제품 원본 이미지 조회 (02 §1-6).
 *
 * <p>세션은 1-3·1-4 를 호출해 만든다 — 실제 흐름에서 이미지가 붙는 경로가 그것뿐이다.
 * mock confidence 는 게이트 임계값 위로 고정한다(APPROVE 확정을 쓰기 때문).
 */
@SpringBootTest(properties = {
        "inference.mock.min-confidence=0.95",
        "inference.mock.max-confidence=0.99"
})
@Testcontainers
@Transactional
class ProductImageControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String MEASURE_PATH = "/api/v1/inbound/measurements";
    private static final String JUICE = "8801234500011";
    private static final String JUICE_IMAGE = "https://placehold.co/300?text=juice";

    private static final String HANDLING =
            """
            "handling": { "refrigerate": true, "fragile": true, "irregular": false }""";

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired MeasurementSessionRepository sessionRepository;
    @PersistenceContext EntityManager em;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Long juiceId() {
        return productRepository.findByGtin(JUICE).map(Product::id).orElseThrow();
    }

    private String imagesPath(Long productId) {
        return "/api/v1/products/" + productId + "/images";
    }

    /** 1-3 촬영 → 세션 id */
    private Long measure(Long productId) throws Exception {
        mvc.perform(post(MEASURE_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "productId": %d }""".formatted(productId)))
                .andExpect(status().isOk());
        return sessionRepository.findByProductIdAndStatusIn(productId,
                List.of(MeasurementStatus.INFERRED, MeasurementStatus.MEASURE_FAILED)).getFirst().getId();
    }

    private void confirmApprove(Long sessionId) throws Exception {
        mvc.perform(post(MEASURE_PATH + "/" + sessionId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"method\": \"APPROVE\", " + HANDLING + " }"))
                .andExpect(status().isOk());
    }

    @Test
    void 확정된_촬영본이_있으면_3장을_돌려준다() throws Exception {
        Long productId = juiceId();
        Long sessionId = measure(productId);
        confirmApprove(sessionId);
        em.flush();
        em.clear();

        mvc.perform(get(imagesPath(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MEASUREMENT"))
                .andExpect(jsonPath("$.images.length()").value(3))
                .andExpect(jsonPath("$.images[0].cameraNo").value(1))
                .andExpect(jsonPath("$.images[0].url").value("/files/m/%d-1.jpg".formatted(sessionId)))
                .andExpect(jsonPath("$.images[2].cameraNo").value(3));
    }

    @Test
    void 촬영_이력이_없으면_마스터_이미지로_대체한다() throws Exception {
        // seed 상품은 dim_status=NONE 이라 확정 세션이 없다
        mvc.perform(get(imagesPath(juiceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MASTER_FALLBACK"))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0].cameraNo").value(nullValue()))
                .andExpect(jsonPath("$.images[0].url").value(JUICE_IMAGE));
    }

    @Test
    void 확정_전_세션만_있으면_아직_대체_이미지다() throws Exception {
        // INFERRED 세션에도 이미지가 붙지만, 확정 전에는 촬영본으로 인정하지 않는다 —
        // 재촬영으로 폐기될 수 있는 값이라 출고 화면이 참조하면 안 된다
        Long productId = juiceId();
        measure(productId);
        em.flush();
        em.clear();

        mvc.perform(get(imagesPath(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MASTER_FALLBACK"));
    }

    @Test
    void 재촬영_후_확정하면_최신_세션의_이미지를_돌려준다() throws Exception {
        Long productId = juiceId();
        measure(productId);
        Long secondSessionId = measure(productId);   // 첫 세션은 DISCARDED 된다
        confirmApprove(secondSessionId);
        em.flush();
        em.clear();

        mvc.perform(get(imagesPath(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MEASUREMENT"))
                .andExpect(jsonPath("$.images[0].url")
                        .value("/files/m/%d-1.jpg".formatted(secondSessionId)));
    }

    @Test
    void 수기_확정이라_촬영본이_없으면_대체_이미지다() throws Exception {
        // MEASURE_FAILED 세션은 이미지가 없다. 수기 확정해도 촬영본은 여전히 없으므로 fallback 이다
        Long productId = juiceId();
        Long sessionId = measure(productId);
        em.createQuery("delete from MeasurementImage i where i.session.id = :id")
                .setParameter("id", sessionId)
                .executeUpdate();
        em.clear();

        mvc.perform(post(MEASURE_PATH + "/" + sessionId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "method": "MANUAL",
                                  "dims": { "widthCm": 6.5, "lengthCm": 6.5, "heightCm": 21.0 },
                                  %s }""".formatted(HANDLING)))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mvc.perform(get(imagesPath(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MASTER_FALLBACK"))
                .andExpect(jsonPath("$.images[0].url").value(JUICE_IMAGE));
    }

    @Test
    void 없는_상품이면_404_PRODUCT_NOT_FOUND() throws Exception {
        mvc.perform(get(imagesPath(999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail.productId").value(999_999));
    }
}
