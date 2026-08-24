package com.awesome.backend.outbound.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.outbound.controller.BoxTypeResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * GET /api/v1/box-types 통합 테스트. 실제 서블릿 컨테이너(RANDOM_PORT)로 띄워 HTTP 레이어까지
 * 전부 통과시킨다.
 *
 * <p>테스트 방식 선택: 처음에는 TestRestTemplate을 시도했으나, Spring Boot 4.1.0에서 test 관련
 * 모듈이 기술별로 잘게 쪼개지면서 TestRestTemplate은 spring-boot-test(현재 의존성)가 아니라
 * 별도 모듈(spring-boot-resttestclient 계열)로 옮겨갔고, 이 프로젝트의 build.gradle에는 그
 * 모듈이 없어 컴파일이 안 됐다(package org.springframework.boot.test.web.client does not
 * exist). MockMvc의 @AutoConfigureMockMvc 역시 spring-boot-webmvc-test 모듈 소관이라 마찬가지로
 * 없다. Tester는 src/test/java 하위 새 파일 생성 외의 쓰기 권한이 없어 build.gradle을 건드릴 수
 * 없으므로, 새 의존성 없이 이미 클래스패스에 있는 것만으로 검증하는 쪽을 택했다: JDK 내장
 * java.net.http.HttpClient로 @LocalServerPort(spring-boot-test에 포함되어 있음, 클래스는
 * org.springframework.boot.test.web.server.LocalServerPort)가 알려주는 실제 포트에 진짜 소켓
 * 요청을 보내고, Boot가 자동 구성한 ObjectMapper 빈으로 응답 JSON을 BoxTypeResponse[]로
 * 역직렬화한다. RANDOM_PORT + 실제 임베디드 톰캣이라는 조건과 가장 잘 맞고, MockMvc처럼 서블릿을
 * 목업하지 않고 실제 네트워크 왕복을 거치는 블랙박스 검증이 된다.
 *
 * <p>쓰기가 없는 순수 조회라 InventoryServiceIT처럼 롤백이 필요 없어 @Transactional은 생략했다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BoxTypeControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    @LocalServerPort int port;

    @Autowired ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void 박스타입_목록은_id_오름차순_5건을_반환한다() throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/box-types"))
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        BoxTypeResponse[] body = objectMapper.readValue(response.body(), BoxTypeResponse[].class);
        assertThat(body).isNotNull();
        assertThat(body).hasSize(5);

        assertThat(body)
                .extracting(BoxTypeResponse::boxTypeId)
                .containsExactly(1L, 2L, 3L, 4L, 5L);

        // V2__seed.sql box_type INSERT의 A호 행과 정확히 일치해야 한다.
        BoxTypeResponse aHo = body[0];
        assertThat(aHo.name()).isEqualTo("A호");
        assertThat(aHo.innerCm())
                .containsExactly(
                        BigDecimal.valueOf(22.0).setScale(1),
                        BigDecimal.valueOf(19.0).setScale(1),
                        BigDecimal.valueOf(9.0).setScale(1));
        assertThat(aHo.stockQty()).isEqualTo(100);

        BoxTypeResponse eHo = body[4];
        assertThat(eHo.name()).isEqualTo("E호");
        assertThat(eHo.innerCm())
                .containsExactly(
                        BigDecimal.valueOf(48.0).setScale(1),
                        BigDecimal.valueOf(38.0).setScale(1),
                        BigDecimal.valueOf(34.0).setScale(1));
    }
}
