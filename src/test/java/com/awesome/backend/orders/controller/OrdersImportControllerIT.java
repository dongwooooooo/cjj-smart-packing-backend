package com.awesome.backend.orders.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inventory.service.InventoryService;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 명세 §7 테스트 20번 — 1층 검증(배치 전체 400 VALIDATION_ERROR).
 * 여기서 확인하는 건 요청이 통과하느냐 배치 전체가 거부되느냐와
 * 에러 포맷(§0 공통 에러)이다. 주문별 거부(2층)는 OrdersImportRejectionIT가 맡는다.
 *
 * MockMvc는 @AutoConfigureMockMvc 대신 WebApplicationContext로 직접 만든다 —
 * Spring Boot 4.1의 starter-test에 해당 오토컨피그 모듈이 없다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class OrdersImportControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/admin/orders/import";

    // V2 seed의 시연 상품
    private static final String JUICE = "8801234500011";
    private static final String CHIP = "8801234500042";
    private static final String UNKNOWN_GTIN = "9999999999999";

    @Autowired WebApplicationContext context;
    @Autowired OrderRepository orderRepository;
    @Autowired InventoryService inventoryService;

    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        // 편성은 치수가 있어야 돌아간다. seed 상품은 치수가 비어 있어(dim_status=NONE)
        // 입고 촬영으로 확정될 값을 여기서 미리 채운다.
        dimensions(JUICE, 5.0, 5.0, 2.0);
        dimensions(CHIP, 5.0, 5.0, 2.0);
    }

    private void dimensions(String gtin, double widthCm, double lengthCm, double heightCm) {
        jdbcTemplate.update("""
                update product set width_cm = ?, length_cm = ?, height_cm = ?, dim_status = 'CONFIRMED'
                where gtin = ?
                """, widthCm, lengthCm, heightCm, gtin);
    }

    @Test
    void 정상_배치는_접수된다() throws Exception {
        // 2층 검증(§3)이 가용재고를 보므로, 통과하려면 재고가 있어야 한다
        inventoryService.recordInbound(JUICE, 3);
        inventoryService.recordInbound(CHIP, 1);

        String body = """
                {
                  "batchId": "B-0821-1",
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 3 }, { "gtin": "%s", "qty": 1 } ] }
                  ]
                }
                """.formatted(JUICE, CHIP);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("B-0821-1"))
                .andExpect(jsonPath("$.rejected").isArray())
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void batchId가_없으면_배치_전체를_거부한다() throws Exception {
        String body = """
                {
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 1 } ] }
                  ]
                }
                """.formatted(JUICE);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.fields.batchId").exists());
    }

    @Test
    void 수량이_0이면_배치_전체를_거부한다() throws Exception {
        String body = """
                {
                  "batchId": "B-0821-1",
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 0 } ] }
                  ]
                }
                """.formatted(JUICE);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void orders가_빈_배열이면_배치_전체를_거부한다() throws Exception {
        String body = """
                { "batchId": "B-0821-1", "orders": [] }
                """;

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.fields.orders").exists());
    }

    @Test
    void items가_빈_배열이면_배치_전체를_거부한다() throws Exception {
        String body = """
                {
                  "batchId": "B-0821-1",
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00", "items": [] }
                  ]
                }
                """;

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 미등록_GTIN이_있으면_배치_전체를_거부한다() throws Exception {
        String body = """
                {
                  "batchId": "B-0821-1",
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 1 } ] },
                    { "receiptNo": "R-20260821-0002", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:01:00",
                      "items": [ { "gtin": "%s", "qty": 1 } ] }
                  ]
                }
                """.formatted(JUICE, UNKNOWN_GTIN);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.unknownGtins[0]").value(UNKNOWN_GTIN));
    }

    @Test
    void 배치_안에서_주문번호가_겹치면_배치_전체를_거부한다() throws Exception {
        String body = """
                {
                  "batchId": "B-0821-1",
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 1 } ] },
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:01:00",
                      "items": [ { "gtin": "%s", "qty": 2 } ] }
                  ]
                }
                """.formatted(JUICE, JUICE);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.duplicateReceiptNos[0]").value("R-20260821-0001"));
    }

    @Test
    void 이미_접수된_주문번호를_다시_보내면_배치_전체를_거부한다() throws Exception {
        orderRepository.save(new Order("R-20260821-0001", "SEOUL", "B-0821-1",
                LocalDateTime.of(2026, 8, 21, 9, 0)));

        String body = """
                {
                  "batchId": "B-0821-1",
                  "orders": [
                    { "receiptNo": "R-20260821-0001", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 1 } ] }
                  ]
                }
                """.formatted(JUICE);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.existingReceiptNos[0]").value("R-20260821-0001"));
    }
}
