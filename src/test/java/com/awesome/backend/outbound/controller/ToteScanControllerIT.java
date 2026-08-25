package com.awesome.backend.outbound.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.entity.Order;
import com.awesome.backend.orders.repository.LineRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Shipment;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
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
 * POST /api/v1/totes/scan 통합 테스트. docs/02-api-spec.md 3-5 (D-14) — 포장 화면 진입점.
 *
 * <p>ShipmentDetailControllerIT/LineShipmentControllerIT와 같은 RANDOM_PORT + JDK HttpClient
 * 블랙박스 방식을 쓰고, 같은 이유로 {@code @Transactional}을 생략한다(실 HTTP 요청은 임베디드
 * 톰캣의 별도 스레드·트랜잭션에서 처리되므로 테스트를 트랜잭션으로 감싸면 setup에서 저장한 행이
 * 그 스레드에서 보이지 않게 된다). 같은 클래스 안 테스트끼리 V2 seed 토트(TOTE-001..010) 10개를
 * 공유하므로, 테스트마다 서로 다른 인덱스의 토트를 골라 활성 할당 유니크 제약과 상태 간섭을 피한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ToteScanControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired LineRepository lineRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ToteRepository toteRepository;
    @Autowired ToteAssignmentRepository toteAssignmentRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 활성_할당_있는_토트_스캔은_PACKING으로_전이하고_shipment_상세를_반환한다()
            throws IOException, InterruptedException {
        Shipment shipment = createShipment(Shipment.Status.TOTE_ASSIGNED);
        Tote tote = toteRepository.findAll().get(0);
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), shipment.id()));

        HttpResponse<String> response = post(tote.barcode());
        assertThat(response.statusCode()).isEqualTo(200);

        ShipmentDetailResponse body = objectMapper.readValue(response.body(), ShipmentDetailResponse.class);
        assertThat(body.shipmentId()).isEqualTo(shipment.id());
        assertThat(body.status()).isEqualTo("PACKING");
        assertThat(body.tote()).isNotNull();
        assertThat(body.tote().barcode()).isEqualTo(tote.barcode());

        Shipment persisted = shipmentRepository.findById(shipment.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(Shipment.Status.PACKING);
    }

    @Test
    void 이미_PACKING인_shipment의_토트_재스캔은_전이_없이_상세만_반환한다()
            throws IOException, InterruptedException {
        Shipment shipment = createShipment(Shipment.Status.PACKING);
        Tote tote = toteRepository.findAll().get(1);
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), shipment.id()));

        HttpResponse<String> response = post(tote.barcode());
        assertThat(response.statusCode()).isEqualTo(200);

        ShipmentDetailResponse body = objectMapper.readValue(response.body(), ShipmentDetailResponse.class);
        assertThat(body.shipmentId()).isEqualTo(shipment.id());
        assertThat(body.status()).isEqualTo("PACKING");

        Shipment persisted = shipmentRepository.findById(shipment.id()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(Shipment.Status.PACKING);
    }

    @Test
    void 존재하지_않는_barcode는_404_TOTE_NOT_ASSIGNED를_반환한다() throws IOException, InterruptedException {
        HttpResponse<String> response = post("NO-SUCH-BARCODE-" + System.nanoTime());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("TOTE_NOT_ASSIGNED");
    }

    @Test
    void 활성_할당_없는_토트는_404_TOTE_NOT_ASSIGNED를_반환한다() throws IOException, InterruptedException {
        Shipment shipment = createShipment(Shipment.Status.PACKED);
        Tote tote = toteRepository.findAll().get(2);
        ToteAssignment assignment = new ToteAssignment(tote.id(), shipment.id());
        assignment.release();
        toteAssignmentRepository.save(assignment);

        HttpResponse<String> response = post(tote.barcode());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("TOTE_NOT_ASSIGNED");
    }

    private Shipment createShipment(Shipment.Status status) {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));
        Shipment shipment = shipmentRepository.save(new Shipment(order.id(), 1, line.id(), 1L, false));
        ReflectionTestUtils.setField(shipment, "status", status);
        return shipmentRepository.save(shipment);
    }

    private HttpResponse<String> post(String barcode) throws IOException, InterruptedException {
        String json = """
                { "barcode": "%s" }
                """.formatted(barcode);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/totes/scan"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
