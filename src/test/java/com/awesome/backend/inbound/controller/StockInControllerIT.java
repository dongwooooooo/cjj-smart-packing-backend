package com.awesome.backend.inbound.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
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

/** 수량 입고 (02 §1-5). */
@SpringBootTest
@Testcontainers
@Transactional
class StockInControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/inbound/stock-in";
    private static final String JUICE = "8801234500011";

    @Autowired WebApplicationContext context;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @PersistenceContext EntityManager em;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Long juiceId() {
        return productRepository.findByGtin(JUICE).map(Product::id).orElseThrow();
    }

    private String body(Long productId, int qty) {
        return """
                { "productId": %d, "qty": %d }""".formatted(productId, qty);
    }

    @Test
    void 입고하면_재고가_늘고_반영된_수량을_돌려준다() throws Exception {
        Long productId = juiceId();
        int before = productRepository.findById(productId).orElseThrow().stockQty();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId, 24)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.stockQty").value(before + 24));

        em.flush();
        em.clear();
        assertThat(productRepository.findById(productId).orElseThrow().stockQty())
                .isEqualTo(before + 24);
    }

    @Test
    void 두_번_입고하면_누적된다() throws Exception {
        Long productId = juiceId();
        int before = productRepository.findById(productId).orElseThrow().stockQty();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId, 10)))
                .andExpect(status().isOk());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQty").value(before + 15));
    }

    @Test
    void 장부에_INBOUND_기록이_남는다() throws Exception {
        // 재고 캐시(stock_qty)만 바뀌고 장부가 비면 나중에 대사를 못 한다 — 창구를 거치는 이유
        Long productId = juiceId();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId, 7)))
                .andExpect(status().isOk());

        em.flush();
        em.clear();
        List<InventoryTx> txs = inventoryTxRepository.findByProductIdOrderByIdAsc(productId);
        assertThat(txs).isNotEmpty();
        InventoryTx last = txs.getLast();
        assertThat(last.txType()).isEqualTo(InventoryTx.TxType.INBOUND);
        assertThat(last.qtyDelta()).isEqualTo(7);
    }

    @Test
    void 없는_상품이면_404_PRODUCT_NOT_FOUND() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(999_999L, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail.productId").value(999_999));
    }

    @Test
    void 수량이_0이면_400() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(juiceId(), 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 수량이_음수면_400() throws Exception {
        // 재고 감소는 관리자 보정 몫이다 — 입고로 줄일 수 없다
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(juiceId(), -5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void productId가_없으면_400() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{ \"qty\": 5 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 치수_미확정_상품도_입고된다() throws Exception {
        // 1-1 REGISTERED 분기는 촬영을 건너뛰고 바로 1-5 로 온다. seed 상품은 dim_status=NONE 이라
        // 확정 여부를 조건으로 걸면 그 흐름이 막힌다
        Long productId = juiceId();
        assertThat(productRepository.findById(productId).orElseThrow().hasConfirmedDimensions()).isFalse();

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(productId, 3)))
                .andExpect(status().isOk());
    }
}
