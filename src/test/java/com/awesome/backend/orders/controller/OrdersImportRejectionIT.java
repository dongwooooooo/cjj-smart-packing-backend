package com.awesome.backend.orders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inventory.service.InventoryService;
import com.awesome.backend.orders.repository.OrderRepository;
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
 * 명세 §3 2층 — 주문별 거부 (테스트 17·18).
 * 배치는 부분 성공한다. 거부된 주문만 rejected에 실리고 나머지는 통과한다.
 *
 * <p>U2 범위에서는 통과 주문도 아직 저장하지 않으므로 orders·shipments·splitOrders는 0이다.
 * 저장은 U3~U4에서 붙는다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class OrdersImportRejectionIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/admin/orders/import";

    private static final String JUICE = "8801234500011";
    private static final String CHIP = "8801234500042";

    @Autowired WebApplicationContext context;
    @Autowired InventoryService inventoryService;
    @Autowired OrderRepository orderRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 미등록_지역_주문만_거부하고_나머지는_통과시킨다() throws Exception {
        inventoryService.recordInbound(JUICE, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "JEJU",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] },
                            { "receiptNo": "R-2", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(JUICE, JUICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].receiptNo").value("R-1"))
                .andExpect(jsonPath("$.rejected[0].reason").value("UNKNOWN_REGION"))
                .andExpect(jsonPath("$.rejected[0].detail.regionCode").value("JEJU"));
    }

    @Test
    void 재고를_넘는_주문은_요청량과_잔여량을_붙여_거부한다() throws Exception {
        inventoryService.recordInbound(JUICE, 2);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 5 } ] }
                          ]
                        }
                        """.formatted(JUICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.rejected[0].detail.gtin").value(JUICE))
                .andExpect(jsonPath("$.rejected[0].detail.requested").value(5))
                .andExpect(jsonPath("$.rejected[0].detail.available").value(2));
    }

    @Test
    void 앞_주문이_먹은_재고는_뒤_주문의_잔여량에서_빠진다() throws Exception {
        inventoryService.recordInbound(JUICE, 5);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 3 } ] },
                            { "receiptNo": "R-2", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 3 } ] }
                          ]
                        }
                        """.formatted(JUICE, JUICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].receiptNo").value("R-2"))
                .andExpect(jsonPath("$.rejected[0].reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.rejected[0].detail.requested").value(3))
                .andExpect(jsonPath("$.rejected[0].detail.available").value(2));
    }

    @Test
    void 거부된_주문의_수량은_뒤_주문의_잔여량을_줄이지_않는다() throws Exception {
        inventoryService.recordInbound(JUICE, 5);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 6 } ] },
                            { "receiptNo": "R-2", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 5 } ] }
                          ]
                        }
                        """.formatted(JUICE, JUICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].receiptNo").value("R-1"));
    }

    @Test
    void 지역과_재고가_함께_어긋나면_지역_사유로_거부한다() throws Exception {
        inventoryService.recordInbound(JUICE, 1);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "JEJU",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 9 } ] }
                          ]
                        }
                        """.formatted(JUICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0].reason").value("UNKNOWN_REGION"));
    }

    @Test
    void 한_주문_안의_같은_상품_수량은_합쳐서_판단한다() throws Exception {
        inventoryService.recordInbound(JUICE, 4);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 3 },
                                         { "gtin": "%s", "qty": 3 },
                                         { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(JUICE, JUICE, CHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].reason").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.rejected[0].detail.requested").value(6));
    }

    @Test
    void 거부된_주문은_주문_레코드를_만들지_않는다() throws Exception {
        inventoryService.recordInbound(JUICE, 1);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "JEJU",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(JUICE)))
                .andExpect(status().isOk());

        assertThat(orderRepository.existsByReceiptNo("R-1")).isFalse();
    }
}
