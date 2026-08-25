package com.awesome.backend.outbound.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.repository.ShipmentRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * PUT /api/v1/shipments/{shipmentId}/box 통합 테스트. docs/02-api-spec.md 3-3 — 박스 오버라이드.
 *
 * <p>ShipmentDetailControllerIT와 같은 RANDOM_PORT + JDK HttpClient 블랙박스 방식을 쓰고, 같은
 * 이유로 {@code @Transactional}을 생략한다. 상태 제약이 없는 API라 shipment는 기본 생성 직후
 * 상태(PLANNED)를 그대로 쓴다 — ReflectionTestUtils로 상태를 바꿔줄 필요가 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShipmentBoxOverrideControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired LineRepository lineRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 정상_오버라이드는_finalBoxId를_바꾸고_응답에_반영한다() throws IOException, InterruptedException {
        // V2 seed box_type A호(id=1)를 recommendedBox로, B호(id=2)로 오버라이드한다.
        Shipment shipment = createShipment(1L);

        HttpResponse<String> response = put(shipment.id(), 2L);
        assertThat(response.statusCode()).isEqualTo(200);

        BoxOverrideResponse body = objectMapper.readValue(response.body(), BoxOverrideResponse.class);
        assertThat(body.shipmentId()).isEqualTo(shipment.id());
        assertThat(body.recommendedBoxId()).isEqualTo(1L);
        assertThat(body.finalBoxId()).isEqualTo(2L);

        Shipment persisted = shipmentRepository.findById(shipment.id()).orElseThrow();
        assertThat(persisted.finalBoxId()).isEqualTo(2L);
        assertThat(persisted.recommendedBoxId()).isEqualTo(1L);
    }

    @Test
    void 존재하지_않는_shipmentId는_404_SHIPMENT_NOT_FOUND를_반환한다() throws IOException, InterruptedException {
        HttpResponse<String> response = put(999999L, 2L);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("SHIPMENT_NOT_FOUND");
    }

    @Test
    void 존재하지_않는_boxTypeId는_404_BOX_TYPE_NOT_FOUND를_반환한다() throws IOException, InterruptedException {
        Shipment shipment = createShipment(1L);

        HttpResponse<String> response = put(shipment.id(), 999999L);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("BOX_TYPE_NOT_FOUND");
    }

    private Shipment createShipment(Long recommendedBoxId) {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));
        return shipmentRepository.save(new Shipment(order.id(), 1, line.id(), recommendedBoxId, false));
    }

    private HttpResponse<String> put(Long shipmentId, Long boxTypeId) throws IOException, InterruptedException {
        String json = """
                { "boxTypeId": %d }
                """.formatted(boxTypeId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/shipments/" + shipmentId + "/box"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
