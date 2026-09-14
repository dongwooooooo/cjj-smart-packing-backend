package com.awesome.backend.orders.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.service.InventoryService;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import java.util.List;
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
 * 명세 §7 19번(저장·상태 전이·토트·응답 수치) + §4-3 초과 치수 거부 + §5 라인 배정.
 *
 * <p>시연 상품은 seed에서 치수가 비어 있다(dim_status=NONE). 편성은 치수가 있어야
 * 돌아가므로 테스트마다 필요한 상품의 치수를 직접 채운다 — 입고 촬영으로 확정되는
 * 값을 미리 넣어두는 것과 같다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class OrdersImportStorageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String PATH = "/api/v1/admin/orders/import";

    private static final String JUICE = "8801234500011";
    private static final String CHIP = "8801234500042";
    private static final String RAMEN = "8801234500066";

    @Autowired WebApplicationContext context;
    @Autowired InventoryService inventoryService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentItemRepository shipmentItemRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired ToteAssignmentRepository toteAssignmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private void dimensions(String gtin, double widthCm, double lengthCm, double heightCm) {
        jdbcTemplate.update("""
                update product set width_cm = ?, length_cm = ?, height_cm = ?, dim_status = 'CONFIRMED'
                where gtin = ?
                """, widthCm, lengthCm, heightCm, gtin);
    }

    private void weight(String gtin, double weightKg) {
        jdbcTemplate.update("update product set weight_kg = ? where gtin = ?", weightKg, gtin);
    }

    @Test
    void 통과_주문은_주문과_배송단위로_저장되고_응답에_수치가_찬다() throws Exception {
        dimensions(CHIP, 5.0, 5.0, 2.0);
        inventoryService.recordInbound(CHIP, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 2 } ] },
                            { "receiptNo": "R-2", "regionCode": "BUSAN",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP, CHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(2))
                .andExpect(jsonPath("$.shipments").value(2))
                .andExpect(jsonPath("$.splitOrders").value(0))
                .andExpect(jsonPath("$.rejected").isEmpty());

        Order saved = orderRepository.findAll().stream()
                .filter(o -> o.receiptNo().equals("R-1")).findFirst().orElseThrow();
        assertThat(saved.status()).isEqualTo(Order.Status.ALLOCATED);

        List<Shipment> shipments = shipmentRepository.findByOrderIdOrderBySeqNoAsc(saved.id());
        assertThat(shipments).hasSize(1);
        Shipment shipment = shipments.getFirst();
        assertThat(shipment.status()).isEqualTo(Shipment.Status.TOTE_ASSIGNED);

        ToteAssignment assignment = toteAssignmentRepository
                .findByShipmentIdAndReleasedAtIsNull(shipment.id()).orElseThrow();
        Tote tote = toteRepository.findById(assignment.toteId()).orElseThrow();
        assertThat(tote.status()).isEqualTo(Tote.Status.ASSIGNED);

        Long chipId = productRepository.findByGtin(CHIP).orElseThrow().id();
        assertThat(shipmentItemRepository.allocatedQty(chipId)).isEqualTo(3);
    }

    @Test
    void 배송단위가_나뉘면_토트도_따로_붙는다() throws Exception {
        dimensions(RAMEN, 30.0, 30.0, 30.0);
        inventoryService.recordInbound(RAMEN, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 2 } ] }
                          ]
                        }
                        """.formatted(RAMEN)))
                .andExpect(status().isOk());

        Long orderId = orderRepository.findAll().stream()
                .filter(o -> o.receiptNo().equals("R-1")).findFirst().orElseThrow().id();
        List<Shipment> shipments = shipmentRepository.findByOrderIdOrderBySeqNoAsc(orderId);
        assertThat(shipments).hasSize(2);

        List<Long> toteIds = shipments.stream()
                .map(s -> toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(s.id())
                        .orElseThrow().toteId())
                .toList();
        assertThat(toteIds).doesNotHaveDuplicates();
    }

    @Test
    void 한_박스에_안_들어가는_주문은_배송단위가_나뉘고_분할로_센다() throws Exception {
        // E호 유효 내치수 45×35×31cm — 30×30×30 두 개는 한 박스에 못 들어간다
        dimensions(RAMEN, 30.0, 30.0, 30.0);
        inventoryService.recordInbound(RAMEN, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 2 } ] }
                          ]
                        }
                        """.formatted(RAMEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.shipments").value(2))
                .andExpect(jsonPath("$.splitOrders").value(1));
    }

    @Test
    void 어떤_박스에도_안_들어가는_낱개는_해당_주문만_거부한다() throws Exception {
        dimensions(JUICE, 100.0, 100.0, 100.0);
        dimensions(CHIP, 5.0, 5.0, 2.0);
        inventoryService.recordInbound(JUICE, 10);
        inventoryService.recordInbound(CHIP, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] },
                            { "receiptNo": "R-2", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(JUICE, CHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].receiptNo").value("R-1"))
                .andExpect(jsonPath("$.rejected[0].reason").value("OVERSIZED_ITEM"))
                .andExpect(jsonPath("$.rejected[0].detail.gtin").value(JUICE));

        assertThat(orderRepository.existsByReceiptNo("R-1")).isFalse();
        assertThat(orderRepository.existsByReceiptNo("R-2")).isTrue();
    }

    @Test
    void 무게_한도를_넘는_낱개는_해당_주문만_거부한다() throws Exception {
        // 치수는 A호에도 들어가지만 25kg은 접수 한도 20kg을 넘어 나눠 담아도 해결되지 않는다
        dimensions(JUICE, 10.0, 10.0, 10.0);
        weight(JUICE, 25.0);
        dimensions(CHIP, 5.0, 5.0, 2.0);
        inventoryService.recordInbound(JUICE, 10);
        inventoryService.recordInbound(CHIP, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] },
                            { "receiptNo": "R-2", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(JUICE, CHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].receiptNo").value("R-1"))
                .andExpect(jsonPath("$.rejected[0].reason").value("OVERWEIGHT_ITEM"))
                .andExpect(jsonPath("$.rejected[0].detail.gtin").value(JUICE))
                .andExpect(jsonPath("$.rejected[0].detail.weightKg").value(25.0));

        assertThat(orderRepository.existsByReceiptNo("R-1")).isFalse();
        assertThat(orderRepository.existsByReceiptNo("R-2")).isTrue();
    }

    @Test
    void 초과_치수로_거부된_주문의_수량은_뒤_주문의_잔여량을_줄이지_않는다() throws Exception {
        dimensions(JUICE, 100.0, 100.0, 100.0);
        inventoryService.recordInbound(JUICE, 3);

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
                .andExpect(jsonPath("$.rejected.length()").value(2))
                .andExpect(jsonPath("$.rejected[0].reason").value("OVERSIZED_ITEM"))
                .andExpect(jsonPath("$.rejected[1].reason").value("OVERSIZED_ITEM"));
    }

    @Test
    void 활성_라인이_없는_지역의_주문은_거부한다() throws Exception {
        dimensions(CHIP, 5.0, 5.0, 2.0);
        inventoryService.recordInbound(CHIP, 10);
        jdbcTemplate.update("update line set status = 'PAUSED' where region_code = 'BUSAN'");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "BUSAN",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] },
                            { "receiptNo": "R-2", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:01:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP, CHIP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].receiptNo").value("R-1"))
                .andExpect(jsonPath("$.rejected[0].reason").value("NO_ACTIVE_LINE"))
                .andExpect(jsonPath("$.rejected[0].detail.regionCode").value("BUSAN"));
    }

    @Test
    void 배송단위는_주문의_배송지역_라인에_배정된다() throws Exception {
        dimensions(CHIP, 5.0, 5.0, 2.0);
        inventoryService.recordInbound(CHIP, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "BUSAN",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP)))
                .andExpect(status().isOk());

        Long orderId = orderRepository.findAll().stream()
                .filter(o -> o.receiptNo().equals("R-1")).findFirst().orElseThrow().id();
        Long busanLineId = jdbcTemplate.queryForObject(
                "select id from line where region_code = 'BUSAN' and status = 'ACTIVE' order by id limit 1",
                Long.class);
        Long assigned = jdbcTemplate.queryForObject(
                "select line_id from shipment where order_id = ?", Long.class, orderId);

        assertThat(assigned).isEqualTo(busanLineId);
    }

    @Test
    void 치수는_있어도_확정_전이면_배치_전체를_거부한다() throws Exception {
        // 비전 추론값만 들어오고 작업자 확정 전인 상태. 센터 치수 보유 여부의 정본은 dim_status다
        jdbcTemplate.update("""
                update product set width_cm = 5.0, length_cm = 5.0, height_cm = 2.0, dim_status = 'NONE'
                where gtin = ?
                """, CHIP);
        inventoryService.recordInbound(CHIP, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.gtin").value(CHIP));
    }

    @Test
    void 치수가_확정되지_않은_상품이_있으면_배치_전체를_거부한다() throws Exception {
        inventoryService.recordInbound(CHIP, 10);

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "batchId": "B-0821-1",
                          "orders": [
                            { "receiptNo": "R-1", "regionCode": "SEOUL",
                              "orderedAt": "2026-08-21T09:00:00",
                              "items": [ { "gtin": "%s", "qty": 1 } ] }
                          ]
                        }
                        """.formatted(CHIP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail.gtin").value(CHIP));
    }
}
