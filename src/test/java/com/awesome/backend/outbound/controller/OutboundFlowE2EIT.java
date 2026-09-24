package com.awesome.backend.outbound.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.inventory.service.InventoryService;
import com.awesome.backend.orders.controller.OrdersImportResponse;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import com.awesome.backend.support.StockTestSupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 출고 파이프라인 전체를 실제 HTTP로 한 번에 관통하는 End-to-End 테스트.
 * {@code P2-출고포장-백엔드-로드맵.md} Phase D 8번째 항목.
 *
 * <p>출고 도메인(shipment/tote/tote_assignment/box_type)은 API 7개 전부 각자의 통합 테스트
 * (ShipmentDetailControllerIT/ToteScanControllerIT/ShipmentBoxOverrideControllerIT/
 * ShipmentCompleteControllerIT/ShipmentLoadControllerIT/BoxTypeControllerIT/
 * LineShipmentControllerIT)로 개별 커버되지만, 그 테스트들은 하나같이 픽스처를
 * {@code repository.save(...)}와 {@code ReflectionTestUtils.setField(...)}로 직접 주입한다 —
 * 실제 파이프라인이 만들어낸 상태가 아니라 손으로 조립한 상태를 검증한다. 그래서
 * {@code POST /api/v1/admin/orders/import}가 실제로 만든 shipment를 실제 토트 스캔·상세 조회·
 * 포장완료·적재가 처음부터 끝까지 정상적으로 통과하는지는 어디서도 증명되지 않는다 —
 * 이 테스트가 그 공백을 메운다.
 *
 * <p>ShipmentCompleteControllerIT/ToteScanControllerIT와 같은 RANDOM_PORT + JDK HttpClient
 * 블랙박스 방식을 쓰고, 같은 이유로 {@code @Transactional}을 생략한다 — 실제 HTTP 요청은
 * 임베디드 톰캣의 별도 스레드·트랜잭션에서 처리되므로, 테스트 자체를 트랜잭션으로 감싸면
 * setup에서 저장한 행이 그 요청 스레드에서 보이지 않게 된다.
 *
 * <p>흐름 하나(happy path)만 끝까지 잇는다 — 각 단계의 실패 케이스(재고 부족, 잘못된 상태 전이,
 * 미등록 바코드 등)는 이미 위에 나열한 개별 IT가 담당하므로 여기서 중복하지 않는다. 대신 단계
 * 사이를 넘나드는 불변식 — 특히 로드맵이 분할 포장 회귀 방지용으로 명시한
 * {@code Σ shipment_item.qty == Σ order_item.qty} — 을 실제 import 산출물에 대해 확인한다.
 * 이건 어느 개별 IT도 실제 import 결과로는 확인하지 않는 값이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(StockTestSupport.class)
class OutboundFlowE2EIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    // V2 seed의 시연 상품 — 이 클래스 전용으로 CIDER/RAMEN을 쓴다. 클래스마다 Testcontainers
    // Postgres 인스턴스가 따로 뜨므로 다른 IT가 JUICE/GRAPE/CHIP/PIE를 쓰고 있어도 간섭이 없다.
    private static final String CIDER = "8801234500035";
    private static final String RAMEN = "8801234500066";

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired InventoryService inventoryService;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentItemRepository shipmentItemRepository;
    @Autowired BoxTypeRepository boxTypeRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired ToteAssignmentRepository toteAssignmentRepository;
    @Autowired StockTestSupport stock;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 출고지시_접수부터_적재까지_실제_파이프라인을_한번에_관통한다()
            throws IOException, InterruptedException {
        // ── 1. Arrange: 치수 확정 + 입고. seed 상품은 dim_status='NONE', stock_qty=0으로 시작해
        // 편성(치수 필요)도, 심사(재고 필요)도 이 상태로는 통과하지 못한다.
        dimensions(CIDER, 5.0, 5.0, 2.0);
        dimensions(RAMEN, 5.0, 5.0, 2.0);
        inventoryService.recordInbound(CIDER, 10);
        inventoryService.recordInbound(RAMEN, 10);

        long nanos = System.nanoTime();
        String receiptNo = "R-E2E-" + nanos;
        String batchId = "B-E2E-" + nanos;

        // ── 2. 출고지시 접수. 한 주문에 두 상품을 담아 split 없이 shipment 1개로 편성되게 한다
        // (작은 치수라 박스 A호 하나에 다 들어간다).
        String importBody =
                """
                {
                  "batchId": "%s",
                  "orders": [
                    { "receiptNo": "%s", "regionCode": "SEOUL",
                      "orderedAt": "2026-08-21T09:00:00",
                      "items": [ { "gtin": "%s", "qty": 3 }, { "gtin": "%s", "qty": 2 } ] }
                  ]
                }
                """
                        .formatted(batchId, receiptNo, CIDER, RAMEN);

        HttpResponse<String> importResponse = postJson("/api/v1/admin/orders/import", importBody);
        assertThat(importResponse.statusCode()).isEqualTo(200);

        OrdersImportResponse importResult =
                objectMapper.readValue(importResponse.body(), OrdersImportResponse.class);
        assertThat(importResult.rejected()).isEmpty();
        assertThat(importResult.orders()).isEqualTo(1);
        assertThat(importResult.shipments()).isEqualTo(1);
        assertThat(importResult.splitOrders()).isEqualTo(0);

        // ── 3. 실제로 만들어진 shipment를 추측이 아니라 조회로 찾는다.
        Order order = orderRepository.findAll().stream()
                .filter(o -> o.receiptNo().equals(receiptNo))
                .findFirst()
                .orElseThrow();

        List<Shipment> shipments = shipmentRepository.findByOrderIdOrderBySeqNoAsc(order.id());
        assertThat(shipments).hasSize(1);
        Shipment shipment = shipments.getFirst();
        assertThat(shipment.status()).isEqualTo(Shipment.Status.TOTE_ASSIGNED);

        ToteAssignment activeAssignment = toteAssignmentRepository
                .findByShipmentIdAndReleasedAtIsNull(shipment.id())
                .orElseThrow();
        Tote assignedTote = toteRepository.findById(activeAssignment.toteId()).orElseThrow();
        assertThat(assignedTote.status()).isEqualTo(Tote.Status.ASSIGNED);

        // ── 4. 로드맵이 분할 포장 회귀 방지용으로 명시한 불변식:
        // Σ(shipment_item.qty) == Σ(order_item.qty). OrderItem 엔티티엔 getter가 없어(생성자만
        // 노출) JdbcTemplate으로 직접 합을 뽑는다 — src/main 변경 없이 값만 확인하면 되는 목적엔
        // 이걸로 충분하다.
        List<ShipmentItem> shipmentItems = shipmentItemRepository.findByShipmentId(shipment.id());
        int shipmentQtySum = shipmentItems.stream().mapToInt(ShipmentItem::qty).sum();
        Integer orderQtySum = jdbcTemplate.queryForObject(
                "select coalesce(sum(qty), 0) from order_item where order_id = ?", Integer.class, order.id());
        assertThat(orderQtySum).isNotNull();
        assertThat(shipmentQtySum).isEqualTo(orderQtySum);
        assertThat(shipmentQtySum).isEqualTo(5); // CIDER 3 + RAMEN 2

        // ── 5. 토트 스캔 — 포장 화면 진입점. TOTE_ASSIGNED → PACKING.
        String scanBody = "{ \"barcode\": \"%s\" }".formatted(assignedTote.barcode());
        HttpResponse<String> scanResponse = postJson("/api/v1/totes/scan", scanBody);
        assertThat(scanResponse.statusCode()).isEqualTo(200);

        ShipmentDetailResponse scanResult =
                objectMapper.readValue(scanResponse.body(), ShipmentDetailResponse.class);
        assertThat(scanResult.status()).isEqualTo("PACKING");
        assertThat(scanResult.tote()).isNotNull();
        assertThat(scanResult.tote().barcode()).isEqualTo(assignedTote.barcode());

        assertThat(shipmentRepository.findById(shipment.id()).orElseThrow().status())
                .isEqualTo(Shipment.Status.PACKING);

        // ── 6. 상세 조회 — setup으로 만들어진 값과 내적 일치를 확인한다.
        HttpResponse<String> detailResponse = get("/api/v1/shipments/" + shipment.id());
        assertThat(detailResponse.statusCode()).isEqualTo(200);

        ShipmentDetailResponse detail =
                objectMapper.readValue(detailResponse.body(), ShipmentDetailResponse.class);
        assertThat(detail.shipmentId()).isEqualTo(shipment.id());
        assertThat(detail.status()).isEqualTo("PACKING");
        assertThat(detail.recommendedBox()).isNotNull();
        assertThat(detail.recommendedBox().boxTypeId()).isEqualTo(shipment.recommendedBoxId());
        assertThat(detail.finalBox()).isNull(); // 박스 오버라이드를 호출하지 않았다.

        Map<String, Integer> qtyByGtin = detail.items().stream()
                .collect(Collectors.toMap(
                        ShipmentDetailResponse.ItemResponse::gtin,
                        ShipmentDetailResponse.ItemResponse::qty));
        assertThat(qtyByGtin).isEqualTo(Map.of(CIDER, 3, RAMEN, 2));

        // ── 7. 포장 완료 — 상품 재고, box 재고, 토트 해제를 한 트랜잭션에서 확인한다.
        long boxId = shipment.recommendedBoxId(); // final_box를 세팅한 적 없으니 recommended가 charged된다.
        int boxStockBefore = boxTypeRepository.findById(boxId).orElseThrow().stockQty();
        long ciderProductId = productRepository.findByGtin(CIDER).orElseThrow().id();
        long ramenProductId = productRepository.findByGtin(RAMEN).orElseThrow().id();
        int ciderStockBefore = stock.onHand(CIDER);
        int ramenStockBefore = stock.onHand(RAMEN);

        HttpResponse<String> completeResponse = postNoBody("/api/v1/shipments/" + shipment.id() + "/complete");
        assertThat(completeResponse.statusCode()).isEqualTo(200);

        ShipmentCompleteResponse completeResult =
                objectMapper.readValue(completeResponse.body(), ShipmentCompleteResponse.class);
        assertThat(completeResult.shipmentId()).isEqualTo(shipment.id());
        assertThat(completeResult.status()).isEqualTo("PACKED");
        assertThat(completeResult.packedAt()).isNotNull();

        assertThat(stock.onHand(CIDER)).isEqualTo(ciderStockBefore - 3);
        assertThat(stock.onHand(RAMEN)).isEqualTo(ramenStockBefore - 2);

        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(ciderProductId))
                .anySatisfy(tx -> {
                    assertThat(tx.txType()).isEqualTo(InventoryTx.TxType.OUTBOUND_PACKED);
                    assertThat(tx.qtyDelta()).isEqualTo(-3);
                });
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(ramenProductId))
                .anySatisfy(tx -> {
                    assertThat(tx.txType()).isEqualTo(InventoryTx.TxType.OUTBOUND_PACKED);
                    assertThat(tx.qtyDelta()).isEqualTo(-2);
                });

        assertThat(boxTypeRepository.findById(boxId).orElseThrow().stockQty()).isEqualTo(boxStockBefore - 1);

        assertThat(toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipment.id())).isEmpty();
        assertThat(toteRepository.findById(assignedTote.id()).orElseThrow().status()).isEqualTo(Tote.Status.IDLE);

        // ── 8. 적재 — PACKED → LOADED. 응답뿐 아니라 repository 재조회로 실제 반영을 확인한다.
        HttpResponse<String> loadResponse = put("/api/v1/shipments/" + shipment.id() + "/load");
        assertThat(loadResponse.statusCode()).isEqualTo(200);

        ShipmentLoadResponse loadResult =
                objectMapper.readValue(loadResponse.body(), ShipmentLoadResponse.class);
        assertThat(loadResult.shipmentId()).isEqualTo(shipment.id());
        assertThat(loadResult.status()).isEqualTo("LOADED");

        assertThat(shipmentRepository.findById(shipment.id()).orElseThrow().status())
                .isEqualTo(Shipment.Status.LOADED);
    }

    private void dimensions(String gtin, double widthCm, double lengthCm, double heightCm) {
        jdbcTemplate.update(
                """
                update product set width_cm = ?, length_cm = ?, height_cm = ?, dim_status = 'CONFIRMED'
                where gtin = ?
                """,
                widthCm, lengthCm, heightCm, gtin);
    }

    private HttpResponse<String> postJson(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postNoBody(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
