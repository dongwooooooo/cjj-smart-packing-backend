package com.awesome.backend.outbound.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.ShipmentItem;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
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
 * GET /api/v1/shipments/{shipmentId} 통합 테스트. docs/02-api-spec.md 3-2 — 박스 추천 화면의 핵심 API.
 *
 * <p>LineShipmentControllerIT과 같은 RANDOM_PORT + JDK HttpClient 블랙박스 방식을 쓰고, 같은 이유로
 * {@code @Transactional}을 생략한다 — 실제 HTTP 요청은 임베디드 톰캣의 별도 스레드·트랜잭션에서
 * 처리되므로 테스트를 트랜잭션으로 감싸면 setup에서 저장한 행이 커밋되지 않아 그 스레드에서
 * 보이지 않게 된다. 테스트 데이터(주문번호·GTIN)는 System.nanoTime() 기반으로 유일하게 만든다.
 *
 * <p>Shipment에는 PACKING 등으로 전이하는 정상 메서드가 없고 finalBoxId를 채우는 메서드도 없어,
 * LineShipmentControllerIT과 같이 ReflectionTestUtils로 테스트 픽스처의 상태(status, finalBoxId)를
 * 직접 설정한다. Product도 마찬가지로 manual() 팩토리에 없는 취급속성 플래그(is_refrigerate 등)를
 * ReflectionTestUtils로 직접 세팅해 저장한다 — ProductRepository는 프로덕션 코드에서는 읽기
 * 전용이지만, 테스트에서 그 리포지토리로 픽스처를 저장하는 것 자체는 금지 대상이 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShipmentDetailControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired LineRepository lineRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentItemRepository shipmentItemRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired ToteAssignmentRepository toteAssignmentRepository;
    @Autowired ProductRepository productRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 정상_조회는_라인_박스_품목의_파생_취급속성을_함께_반환한다() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));

        // V2 seed box_type A호(id=1)를 recommendedBox로 쓴다.
        Shipment shipment = shipmentRepository.save(new Shipment(order.id(), 1, line.id(), 1L, true));
        setStatus(shipment, Shipment.Status.PACKING);

        // 냉장 필수만 — 중분류를 가공식품(C2010, 과자·비-음료)로 둬서 액체주의 조건에는 안 걸리게 한다.
        Product refrigerated = saveProduct(1, "C2010", true, false, false);
        // 액체주의만 — 파손주의(is_fragile) + 중분류가 음료 대분류(C1010 과채주스 → 대분류 C10 음료).
        Product fragileBeverage = saveProduct(2, "C1010", false, true, false);

        shipmentItemRepository.save(new ShipmentItem(shipment.id(), refrigerated.id(), 2));
        shipmentItemRepository.save(new ShipmentItem(shipment.id(), fragileBeverage.id(), 3));

        ShipmentDetailResponse body = get(shipment.id());

        assertThat(body.shipmentId()).isEqualTo(shipment.id());
        assertThat(body.orderId()).isEqualTo(order.id());
        assertThat(body.seqNo()).isEqualTo(1);
        assertThat(body.status()).isEqualTo("PACKING");
        assertThat(body.line().lineId()).isEqualTo(line.id());
        assertThat(body.line().name()).isEqualTo(line.name());
        assertThat(body.recommendedBox().boxTypeId()).isEqualTo(1L);
        assertThat(body.recommendedBox().name()).isEqualTo("A호");
        assertThat(body.fillerRecommended()).isTrue();
        assertThat(body.items()).hasSize(2);

        ShipmentDetailResponse.ItemResponse refrigeratedItem = body.items().stream()
                .filter(i -> i.productId().equals(refrigerated.id()))
                .findFirst().orElseThrow();
        assertThat(refrigeratedItem.gtin()).isEqualTo(refrigerated.gtin());
        assertThat(refrigeratedItem.name()).isEqualTo(refrigerated.name());
        assertThat(refrigeratedItem.qty()).isEqualTo(2);
        assertThat(refrigeratedItem.handling()).containsExactly("REFRIGERATE");

        ShipmentDetailResponse.ItemResponse fragileItem = body.items().stream()
                .filter(i -> i.productId().equals(fragileBeverage.id()))
                .findFirst().orElseThrow();
        assertThat(fragileItem.qty()).isEqualTo(3);
        assertThat(fragileItem.handling()).containsExactly("LIQUID_CAUTION");
    }

    @Test
    void 활성_토트가_있으면_tote가_채워지고_없으면_null이다() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));

        Shipment withTote = shipmentRepository.save(new Shipment(order.id(), 1, line.id(), 1L, false));
        Shipment withoutTote = shipmentRepository.save(new Shipment(order.id(), 2, line.id(), 1L, false));

        Tote tote = toteRepository.findAll().get(0);
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), withTote.id()));

        ShipmentDetailResponse withToteBody = get(withTote.id());
        assertThat(withToteBody.tote()).isNotNull();
        assertThat(withToteBody.tote().toteId()).isEqualTo(tote.id());
        assertThat(withToteBody.tote().barcode()).isEqualTo(tote.barcode());

        ShipmentDetailResponse withoutToteBody = get(withoutTote.id());
        assertThat(withoutToteBody.tote()).isNull();
    }

    @Test
    void finalBox는_없으면_null_있으면_채워진다() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));

        Shipment noFinalBox = shipmentRepository.save(new Shipment(order.id(), 1, line.id(), 1L, false));

        Shipment withFinalBox = shipmentRepository.save(new Shipment(order.id(), 2, line.id(), 1L, false));
        ReflectionTestUtils.setField(withFinalBox, "finalBoxId", 2L);
        shipmentRepository.save(withFinalBox);

        ShipmentDetailResponse noFinalBoxBody = get(noFinalBox.id());
        assertThat(noFinalBoxBody.finalBox()).isNull();

        ShipmentDetailResponse withFinalBoxBody = get(withFinalBox.id());
        assertThat(withFinalBoxBody.finalBox()).isNotNull();
        assertThat(withFinalBoxBody.finalBox().boxTypeId()).isEqualTo(2L);
        assertThat(withFinalBoxBody.finalBox().name()).isEqualTo("B호");
    }

    @Test
    void 존재하지_않는_shipmentId는_404_SHIPMENT_NOT_FOUND를_반환한다() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/shipments/999999"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("SHIPMENT_NOT_FOUND");
    }

    private void setStatus(Shipment shipment, Shipment.Status status) {
        ReflectionTestUtils.setField(shipment, "status", status);
        shipmentRepository.save(shipment);
    }

    private Product saveProduct(
            int salt, String mediumCategoryCode, boolean refrigerate, boolean fragile, boolean irregular) {
        Product product = Product.manual(
                uniqueGtin(salt), "테스트상품" + salt, mediumCategoryCode, "https://placehold.co/300?text=test");
        ReflectionTestUtils.setField(product, "refrigerate", refrigerate);
        ReflectionTestUtils.setField(product, "fragile", fragile);
        ReflectionTestUtils.setField(product, "irregular", irregular);
        return productRepository.save(product);
    }

    /** product.gtin은 char(13) 유니크 컬럼 — nanoTime 기반으로 자릿수를 정확히 13으로 맞춘다. */
    private String uniqueGtin(int salt) {
        long base = (System.nanoTime() + salt) % 100_000_000_000L;
        return String.format("99%011d", base);
    }

    private ShipmentDetailResponse get(Long shipmentId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/shipments/" + shipmentId))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ShipmentDetailResponse.class);
    }
}
