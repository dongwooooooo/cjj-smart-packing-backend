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
 * GET /api/v1/lines/{lineId}/shipments 통합 테스트. docs/02-api-spec.md 3-1 (D-12).
 *
 * <p>BoxTypeControllerIT와 같은 RANDOM_PORT + JDK HttpClient 블랙박스 방식을 쓴다. 다만 이 API는
 * 쓰기 없이 조회만 하는 BoxType과 달리 테스트가 직접 Order/Shipment/Tote 데이터를 만들어야 하는데,
 * 실제 HTTP 요청은 임베디드 톰캣의 별도 스레드·트랜잭션에서 처리된다. 그래서 InventoryServiceIT처럼
 * 테스트를 @Transactional로 감싸면 setup에서 저장한 행이 커밋되지 않아 HTTP 스레드에서 안 보이게
 * 된다 — 여기서도 @Transactional은 생략한다. 대신 테스트 데이터는 System.nanoTime() 접미사로
 * 유일하게 만들고, 같은 컨테이너(static 필드라 클래스 내 테스트끼리 공유)를 쓰는 다른 테스트가 남긴
 * 행과 섞이지 않도록 매 assertion을 이번에 만든 shipmentId 기준으로 좁혀서 확인한다.
 *
 * <p>Shipment 엔티티에는 PACKING/PACKED로 전이하는 공개 메서드가 없다(assignTote()만
 * TOTE_ASSIGNED로 전이 가능 — 이번 작업 범위에서 엔티티에는 seqNo()/lineId() 접근자 2개만
 * 추가하기로 합의했고 상태 전이 메서드는 임의로 늘리지 않는다). 테스트 데이터로 다른 상태가
 * 필요해서 ReflectionTestUtils로 private status 필드를 직접 설정한 뒤 저장한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LineShipmentControllerIT {

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
    void 라인의_shipment_목록을_상태필터와_토트바코드까지_반환한다() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);
        Order order = orderRepository.save(
                new Order("R-IT-" + System.nanoTime(), line.regionCode(), "B-IT", LocalDateTime.now()));

        // V2 seed box_type A호(id=1) 참조. seqNo는 shipment 유니크 제약(order_id, seq_no) 때문에
        // 한 order 안에서 서로 달라야 한다.
        Shipment toteAssigned = shipmentRepository.save(new Shipment(order.id(), 1, line.id(), 1L, false));
        setStatus(toteAssigned, Shipment.Status.TOTE_ASSIGNED);

        Shipment packing = shipmentRepository.save(new Shipment(order.id(), 2, line.id(), 1L, false));
        setStatus(packing, Shipment.Status.PACKING);

        Shipment packed = shipmentRepository.save(new Shipment(order.id(), 3, line.id(), 1L, false));
        setStatus(packed, Shipment.Status.PACKED);

        Tote tote = toteRepository.findAll().get(0);
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), toteAssigned.id()));

        // (a) status 생략 — 방금 만든 3건이 전부 이 라인 응답에 포함돼야 한다.
        LineShipmentListResponse all = get("/api/v1/lines/" + line.id() + "/shipments");
        assertThat(all.shipments())
                .filteredOn(s -> s.shipmentId().equals(toteAssigned.id())
                        || s.shipmentId().equals(packing.id())
                        || s.shipmentId().equals(packed.id()))
                .hasSize(3);

        // (b) status=PACKING 필터링 — packing 건만, 나머지 두 건은 빠져야 한다.
        LineShipmentListResponse packingOnly =
                get("/api/v1/lines/" + line.id() + "/shipments?status=PACKING");
        assertThat(packingOnly.shipments())
                .extracting(LineShipmentResponse::shipmentId)
                .contains(packing.id())
                .doesNotContain(toteAssigned.id(), packed.id());

        // (c) 활성 tote_assignment가 있는 shipment는 toteBarcode가 채워지고, 없는 shipment는 null.
        LineShipmentResponse toteAssignedDto = all.shipments().stream()
                .filter(s -> s.shipmentId().equals(toteAssigned.id()))
                .findFirst().orElseThrow();
        assertThat(toteAssignedDto.toteBarcode()).isEqualTo(tote.barcode());
        assertThat(toteAssignedDto.receiptNo()).isEqualTo(order.receiptNo());
        assertThat(toteAssignedDto.seqNo()).isEqualTo(1);
        assertThat(toteAssignedDto.status()).isEqualTo("TOTE_ASSIGNED");

        LineShipmentResponse packingDto = all.shipments().stream()
                .filter(s -> s.shipmentId().equals(packing.id()))
                .findFirst().orElseThrow();
        assertThat(packingDto.toteBarcode()).isNull();

        // 존재하지 않는 lineId — 에러 없이 빈 리스트.
        LineShipmentListResponse none = get("/api/v1/lines/999999/shipments");
        assertThat(none.shipments()).isEmpty();
    }

    @Test
    void 유효하지_않은_status는_400_VALIDATION_ERROR를_반환한다() throws IOException, InterruptedException {
        Line line = lineRepository.findAll().get(0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/lines/" + line.id()
                        + "/shipments?status=NOT_A_STATUS"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_ERROR");
    }

    private void setStatus(Shipment shipment, Shipment.Status status) {
        ReflectionTestUtils.setField(shipment, "status", status);
        shipmentRepository.save(shipment);
    }

    private LineShipmentListResponse get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), LineShipmentListResponse.class);
    }
}
