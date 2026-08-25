package com.awesome.backend.inbound.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.MeasurementSession;
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
 * 촬영·추론 (02 §1-3).
 *
 * <p>mock confidence 범위를 게이트 임계값 위로 고정한다 — 기본 설정은 미통과도 가끔 나오도록
 * 임계값을 걸치게 잡혀 있어서, 그대로 두면 테스트가 무작위로 깨진다.
 * 게이트 판정 자체는 MeasurementGateTest 가 결정적으로 검증한다.
 */
@SpringBootTest(properties = {
        "inference.mock.min-confidence=0.95",
        "inference.mock.max-confidence=0.99"
})
@Testcontainers
@Transactional
class MeasurementControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/inbound/measurements";

    /** V2 seed 의 시연 상품 — 분류 C1010(과채주스), 사전 등록 무게 1.080kg (D-10). */
    private static final String JUICE = "8801234500011";

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired MeasurementSessionRepository sessionRepository;
    @Autowired DemoProductRepository demoProductRepository;
    @PersistenceContext EntityManager em;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Long juiceId() {
        return productRepository.findByGtin(JUICE).map(Product::id).orElseThrow();
    }

    private String body(Long productId) {
        return """
                { "productId": %d }""".formatted(productId);
    }

    @Test
    void 촬영하면_추론_치수와_카메라_3장이_내려온다() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(juiceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.status").value("INFERRED"))
                .andExpect(jsonPath("$.inferred.widthCm").isNumber())
                .andExpect(jsonPath("$.inferred.lengthCm").isNumber())
                .andExpect(jsonPath("$.inferred.heightCm").isNumber())
                .andExpect(jsonPath("$.confidence").isNumber())
                .andExpect(jsonPath("$.gatePassed").value(true))
                .andExpect(jsonPath("$.gateFailReasons").isEmpty())
                .andExpect(jsonPath("$.images.length()").value(3))
                .andExpect(jsonPath("$.images[0].cameraNo").value(1))
                .andExpect(jsonPath("$.images[2].cameraNo").value(3))
                .andExpect(jsonPath("$.failReason").doesNotExist());
    }

    @Test
    void 무게는_저울_대신_사전_등록값을_돌려준다() throws Exception {
        // D-10: 시연 환경에 저울이 없어 product.weight_kg 를 조회해 쓴다
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(juiceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(1.080));
    }

    @Test
    void 취급속성_기본값은_분류_매핑에서_온다() throws Exception {
        // V2 seed: C1010(과채주스) → 냉장 O, 파손주의 O, 비정형 X
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(juiceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlingDefaults.refrigerate").value(true))
                .andExpect(jsonPath("$.handlingDefaults.fragile").value(true))
                .andExpect(jsonPath("$.handlingDefaults.irregular").value(false));
    }

    @Test
    void 저장된_치수는_축_규약을_지킨다() throws Exception {
        // D-18: 높이는 그대로, 나머지 두 변은 긴 쪽이 widthCm — 불변식 widthCm >= lengthCm
        Long productId = juiceId();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk());

        MeasurementSession session = sessionRepository
                .findByProductIdAndStatusIn(productId, List.of(MeasurementStatus.INFERRED))
                .getFirst();

        assertThat(session.getInferredWidthCm())
                .isGreaterThanOrEqualTo(session.getInferredLengthCm());
    }

    @Test
    void 재촬영하면_이전_세션은_폐기된다() throws Exception {
        Long productId = juiceId();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk());

        // 미확정 세션은 항상 최신 1개만 남는다 — 게이트 미통과 해제 경로가 재촬영이라
        // 이전 세션이 살아 있으면 어느 것을 확정할지 모호해진다
        assertThat(sessionRepository.findByProductIdAndStatusIn(productId,
                List.of(MeasurementStatus.INFERRED, MeasurementStatus.MEASURE_FAILED))).hasSize(1);
        assertThat(sessionRepository.findByProductIdAndStatusIn(productId,
                List.of(MeasurementStatus.DISCARDED))).hasSize(1);
    }

    @Test
    void 없는_상품이면_404_PRODUCT_NOT_FOUND() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail.productId").value(999_999));
    }

    @Test
    void productId가_없으면_400_VALIDATION_ERROR() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 촬영은_재고를_건드리지_않는다() throws Exception {
        // D-09: 재고 증가는 stock-in(1-5) 한 곳뿐이다
        Long productId = juiceId();
        int before = productRepository.findById(productId).orElseThrow().stockQty();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk());

        assertThat(productRepository.findById(productId).orElseThrow().stockQty()).isEqualTo(before);
    }

    @Test
    void 이미지_경로는_세션과_카메라_번호로_만들어진다() throws Exception {
        Long productId = juiceId();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk());

        MeasurementSession session = sessionRepository
                .findByProductIdAndStatusIn(productId, List.of(MeasurementStatus.INFERRED))
                .getFirst();

        assertThat(session.getImages()).extracting("filePath")
                .containsExactly(
                        "/files/m/%d-1.jpg".formatted(session.getId()),
                        "/files/m/%d-2.jpg".formatted(session.getId()),
                        "/files/m/%d-3.jpg".formatted(session.getId()));
    }

    @Test
    void 데모_이미지가_있는_상품은_그_파일_URL이_실리고_정적으로_열린다() throws Exception {
        // P3 데모 서브시스템이 demo/data/images/{gtin}/cam{n}.jpg 를 두고 demo_product.image_dir 로
        // 가리킨다 (docs/demo-subsystem-spec.md §6). 리셋 API 대신 행만 직접 넣는다.
        String gtin = "8801234500028";
        Long productId = productRepository.findByGtin(gtin).map(Product::id).orElseThrow();
        demoProductRepository.save(new DemoProduct(gtin, DemoProduct.Pool.INBOUND,
                new java.math.BigDecimal("10.0"), new java.math.BigDecimal("8.0"),
                new java.math.BigDecimal("5.0"), "images/" + gtin));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].url").value("/files/m/" + gtin + "/cam1.jpg"))
                .andExpect(jsonPath("$.images[2].url").value("/files/m/" + gtin + "/cam3.jpg"));

        mvc.perform(get("/files/m/" + gtin + "/cam2.jpg"))
                .andExpect(status().isOk());
    }

    @Test
    void 무게가_없는_상품도_촬영은_된다() throws Exception {
        // 저울 미수신은 실패가 아니다 — weightKg 만 null 이고 나머지 흐름은 그대로다 (02 §1-3).
        // seed 상품은 전부 무게가 있어(D-17) 여기서만 비운다. product 쓰기 API 를 거치지
        // 않는 이유는, 무게를 지우는 정상 경로가 명세에 없기 때문이다.
        Long productId = juiceId();
        em.createQuery("update Product p set p.weightKg = null where p.id = :id")
                .setParameter("id", productId)
                .executeUpdate();
        em.clear();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INFERRED"))
                .andExpect(jsonPath("$.weightKg").value(nullValue()));
    }
}
