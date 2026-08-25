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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * PUT /api/v1/shipments/{shipmentId}/load 통합 테스트. docs/02-api-spec.md 3-9 — 적재, 로드맵 Phase D
 * 마지막이자 가장 단순한 API.
 *
 * <p>ShipmentBoxOverrideControllerIT와 같은 RANDOM_PORT + JDK HttpClient 블랙박스 방식을 쓰고, 같은
 * 이유로 {@code @Transactional}을 생략한다. Shipment에는 PACKED로 전이하는 정상 경로(포장완료, PR
 * #20 미머지)가 이 브랜치에 없어 ShipmentDetailControllerIT처럼 ReflectionTestUtils로 픽스처 상태를
 * 직접 세팅한다. 로드맵이 "검증 최소한만"이라 명시한 API라 시나리오도 최소 3개로 제한한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShipmentLoadControllerIT {

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
    void PACKED_상태는_LOADED로_전이하고_응답과_DB에_반영한다() throws IOException, InterruptedException {
        Shipment shipment = createShipment();
        setStatus(shipment, Shipment.Status.PACKED);

        HttpResponse<String> response = put(shipment.id());
        assertThat(response.statusCode()).isEqualTo(200);

        ShipmentLoadResponse body = objectMapper.readValue(response.body(), ShipmentLoadResponse.class);
        assertThat(body.shipmentId()).isEqualTo(shipment.id());
        assertThat(body.status()).isEqualTo("LOADED");

        Shipment persisted = shipmentRepository.findById(shipment.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(Shipment.Status.LOADED);
    }

    @Test
    void PACKED가_아니면_409_INVALID_STATE를_반환한다() throws IOException, InterruptedException {
        Shipment shipment = createShipment();
        setStatus(shipment, Shipment.Status.PACKING);

        HttpResponse<String> response = put(shipment.id());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("INVALID_STATE");
    }

    @Test
    void 존재하지_않는_shipmentId는_404_SHIPMENT_NOT_FOUND를_반환한다() throws IOException, InterruptedException {
        HttpResponse<String> response = put(999999L);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("SHIPMENT_NOT_FOUND");
    }

    private Shipment createShipment() {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));
        return shipmentRepository.save(new Shipment(order.id(), 1, line.id(), 1L, false));
    }

    private void setStatus(Shipment shipment, Shipment.Status status) {
        ReflectionTestUtils.setField(shipment, "status", status);
        shipmentRepository.save(shipment);
    }

    private HttpResponse<String> put(Long shipmentId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/shipments/" + shipmentId + "/load"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
