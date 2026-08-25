package com.awesome.backend.outbound.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.entity.InventoryTx;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.BoxType;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShipmentItemRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /api/v1/shipments/{shipmentId}/complete 통합 테스트. docs/02-api-spec.md 3-8.
 *
 * <p>LineShipmentControllerIT/BoxTypeControllerIT와 같은 RANDOM_PORT + JDK HttpClient 블랙박스
 * 방식을 쓴다. LineShipmentControllerIT의 javadoc이 설명하듯 실제 HTTP 요청은 임베디드 톰캣의 별도
 * 스레드·트랜잭션에서 처리되므로, 테스트 자체를 {@code @Transactional}로 감싸면 setup에서 저장한
 * 행이 HTTP 스레드에서 안 보이게 된다 — 그래서 여기서도 생략한다. 대신 테스트마다 V2 seed의 서로
 * 다른 상품·박스를 전용으로 쓰고, 주문은 System.nanoTime() 접미사로 유일하게 만들어 테스트끼리
 * 데이터가 섞이지 않게 한다.
 *
 * <p>V2 seed 상품 6종 중 이 클래스는 4종을 시나리오별로 전담해 stock_qty 조작이 서로 간섭하지
 * 않게 한다: JUICE(정상 완료 — final_box 우선 확인), CHIP+GRAPE(상품 재고 부족 — 부분 롤백
 * 확인), PIE(박스 재고 부족 — recommended_box 경로 확인). box_type도 마찬가지로 A/B호는 정상
 * 완료 시나리오(추천 vs 확정) 확인용, C호는 재고를 0으로 강제해 박스 품절 시나리오 전담으로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShipmentCompleteControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String JUICE = "8801234500011";
    private static final String GRAPE = "8801234500028";
    private static final String CHIP = "8801234500042";
    private static final String PIE = "8801234500059";

    // V2 seed box_type: A호=1, B호=2, C호=3 (전부 stock_qty=100으로 시작)
    private static final long BOX_A = 1L;
    private static final long BOX_B = 2L;
    private static final long BOX_C = 3L;

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired LineRepository lineRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentItemRepository shipmentItemRepository;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    @Autowired BoxTypeRepository boxTypeRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired ToteAssignmentRepository toteAssignmentRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 정상_완료는_상품재고_확정박스재고_토트해제를_한번에_반영한다() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        setStock(JUICE, 20);
        long juiceProductId = productRepository.findByGtin(JUICE).orElseThrow().id();

        // final_box(B호)가 있으면 recommended_box(A호)가 아니라 final_box 재고가 깎여야 한다.
        Shipment shipment = savePackingShipment(line, BOX_A, BOX_B);
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), juiceProductId, 4));
        Tote tote = assignIdleTote(shipment.id());
        int boxAStockBefore = boxTypeRepository.findById(BOX_A).orElseThrow().stockQty();
        int boxBStockBefore = boxTypeRepository.findById(BOX_B).orElseThrow().stockQty();

        HttpResponse<String> response = post("/api/v1/shipments/" + shipment.id() + "/complete");
        assertThat(response.statusCode()).isEqualTo(200);

        ShipmentCompleteResponse body = objectMapper.readValue(response.body(), ShipmentCompleteResponse.class);
        assertThat(body.shipmentId()).isEqualTo(shipment.id());
        assertThat(body.status()).isEqualTo("PACKED");
        assertThat(body.packedAt()).isNotNull();
        assertThat(body.line().lineId()).isEqualTo(line.id());

        // line.packedCount가 실제 PACKED 개수와 일치.
        long actualPackedCount = shipmentRepository.countByLineIdAndStatus(line.id(), Shipment.Status.PACKED);
        assertThat(body.line().packedCount()).isEqualTo(actualPackedCount);

        // 상품 재고: 4개 차감.
        Product juice = productRepository.findByGtin(JUICE).orElseThrow();
        assertThat(juice.stockQty()).isEqualTo(16);
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(juiceProductId))
                .anySatisfy(tx -> {
                    assertThat(tx.txType()).isEqualTo(InventoryTx.TxType.OUTBOUND_PACKED);
                    assertThat(tx.qtyDelta()).isEqualTo(-4);
                });

        // 박스 재고: final_box(B호)만 1 차감, recommended_box(A호)는 그대로.
        assertThat(boxTypeRepository.findById(BOX_B).orElseThrow().stockQty()).isEqualTo(boxBStockBefore - 1);
        assertThat(boxTypeRepository.findById(BOX_A).orElseThrow().stockQty()).isEqualTo(boxAStockBefore);

        // 토트 할당 해제.
        ToteAssignment assignment = toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipment.id())
                .orElse(null);
        assertThat(assignment).isNull();
        assertThat(toteRepository.findById(tote.id()).orElseThrow().status()).isEqualTo(Tote.Status.IDLE);

        // DB에서 다시 읽어도 PACKED + packedAt 채워짐.
        Shipment reloaded = shipmentRepository.findById(shipment.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(Shipment.Status.PACKED);
    }

    @Test
    void PLANNED_상태에서_호출하면_409_INVALID_STATE() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        Shipment shipment = saveShipmentWithStatus(line, BOX_A, null, Shipment.Status.PLANNED);

        HttpResponse<String> response = post("/api/v1/shipments/" + shipment.id() + "/complete");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("INVALID_STATE");
    }

    @Test
    void 이미_PACKED인_shipment를_재호출하면_409_INVALID_STATE() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        Shipment shipment = saveShipmentWithStatus(line, BOX_A, null, Shipment.Status.PACKED);

        HttpResponse<String> response = post("/api/v1/shipments/" + shipment.id() + "/complete");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("INVALID_STATE");
    }

    @Test
    void 상품_재고_부족이면_409_OUT_OF_STOCK이고_앞선_항목_반영도_전부_롤백된다()
            throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        // 첫 항목(CHIP)은 재고가 충분해 정상 차감될 것 — 트랜잭션이 끝까지 커밋된다면.
        // 두번째 항목(GRAPE)이 재고 부족으로 실패하면 CHIP의 차감도 함께 롤백돼야 한다.
        setStock(CHIP, 10);
        setStock(GRAPE, 2);
        long chipProductId = productRepository.findByGtin(CHIP).orElseThrow().id();
        long grapeProductId = productRepository.findByGtin(GRAPE).orElseThrow().id();

        Shipment shipment = savePackingShipment(line, BOX_A, null);
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), chipProductId, 2));
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), grapeProductId, 5));
        assignIdleTote(shipment.id());

        HttpResponse<String> response = post("/api/v1/shipments/" + shipment.id() + "/complete");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("OUT_OF_STOCK");

        // 부분 반영 없이 전부 롤백됐는지 확인.
        assertThat(shipmentRepository.findById(shipment.id()).orElseThrow().status())
                .isEqualTo(Shipment.Status.PACKING);
        assertThat(toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipment.id())).isPresent();
        // 먼저 처리됐어야 할 CHIP도 재고가 원래대로(트랜잭션 전체 롤백의 핵심 증거).
        assertThat(productRepository.findByGtin(CHIP).orElseThrow().stockQty()).isEqualTo(10);
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(chipProductId)).isEmpty();
    }

    @Test
    void 박스_재고_부족이면_409_OUT_OF_STOCK이고_이미_반영한_상품재고도_롤백된다()
            throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        setStock(PIE, 20);
        long pieProductId = productRepository.findByGtin(PIE).orElseThrow().id();

        // C호를 이 테스트 전용으로 0으로 강제 — final_box 없이 recommended_box(C호) 경로를 탄다.
        BoxType boxC = boxTypeRepository.findById(BOX_C).orElseThrow();
        ReflectionTestUtils.setField(boxC, "stockQty", 0);
        boxTypeRepository.save(boxC);

        Shipment shipment = savePackingShipment(line, BOX_C, null);
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), pieProductId, 3));
        assignIdleTote(shipment.id());

        HttpResponse<String> response = post("/api/v1/shipments/" + shipment.id() + "/complete");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("OUT_OF_STOCK");

        assertThat(shipmentRepository.findById(shipment.id()).orElseThrow().status())
                .isEqualTo(Shipment.Status.PACKING);
        assertThat(toteAssignmentRepository.findByShipmentIdAndReleasedAtIsNull(shipment.id())).isPresent();
        // 상품 재고 차감(3단계)까지는 통과했지만 박스 재고 부족(4단계)으로 실패 — 롤백돼 원복돼야 한다.
        assertThat(productRepository.findByGtin(PIE).orElseThrow().stockQty()).isEqualTo(20);
        assertThat(inventoryTxRepository.findByProductIdOrderByIdAsc(pieProductId)).isEmpty();
        // 박스 재고는 여전히 0(더 깎이지 않음).
        assertThat(boxTypeRepository.findById(BOX_C).orElseThrow().stockQty()).isEqualTo(0);
    }

    private void setStock(String gtin, int qty) {
        Product product = productRepository.findByGtin(gtin).orElseThrow();
        product.changeStockQty(qty);
        productRepository.save(product);
    }

    /** PACKING 상태 + 유일한 order로 shipment를 만든다. finalBoxId는 null 허용. */
    private Shipment savePackingShipment(Line line, long recommendedBoxId, Long finalBoxId) {
        return saveShipmentWithStatus(line, recommendedBoxId, finalBoxId, Shipment.Status.PACKING);
    }

    private Shipment saveShipmentWithStatus(
            Line line, long recommendedBoxId, Long finalBoxId, Shipment.Status status) {
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));
        Shipment shipment = new Shipment(order.id(), 1, line.id(), recommendedBoxId, false);
        if (finalBoxId != null) {
            ReflectionTestUtils.setField(shipment, "finalBoxId", finalBoxId);
        }
        ReflectionTestUtils.setField(shipment, "status", status);
        return shipmentRepository.save(shipment);
    }

    /** 현재 IDLE인 토트 하나를 잡아 ASSIGNED로 바꾸고 shipment에 활성 할당을 건다. */
    private Tote assignIdleTote(Long shipmentId) {
        Tote tote = toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("테스트용 유휴 토트가 없습니다."));
        tote.assign();
        toteRepository.save(tote);
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), shipmentId));
        return tote;
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
