package com.awesome.backend.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.repository.InventoryTxRepository;
import com.awesome.backend.support.StockTestSupport;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(StockTestSupport.class)
class InventoryAdjustmentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String CIDER = "8801234500035";
    private static final String PATH = "/api/v1/admin/inventory/adjustments";

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;
    @Autowired StockTestSupport stock;
    @Autowired ProductRepository productRepository;
    @Autowired InventoryTxRepository inventoryTxRepository;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 같은_키_재전송은_한_번만_반영되고_duplicated를_돌려준다() throws IOException, InterruptedException {
        stock.set(CIDER, 10);
        String key = "adj-" + System.nanoTime();
        String body = """
                {"gtin":"%s","delta":5,"idempotencyKey":"%s","reason":"실사 보정"}""".formatted(CIDER, key);

        HttpResponse<String> first = post(body);
        HttpResponse<String> second = post(body);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        InventoryAdjustmentResponse r1 = objectMapper.readValue(first.body(), InventoryAdjustmentResponse.class);
        InventoryAdjustmentResponse r2 = objectMapper.readValue(second.body(), InventoryAdjustmentResponse.class);
        assertThat(r1.duplicated()).isFalse();
        assertThat(r2.duplicated()).isTrue();
        assertThat(r2.txId()).isEqualTo(r1.txId());
        assertThat(r2.onHandQty()).isEqualTo(15);
        assertThat(stock.onHand(CIDER)).isEqualTo(15);
    }

    @Test
    void 같은_키에_다른_delta면_409() throws IOException, InterruptedException {
        String key = "adj-" + System.nanoTime();
        post("""
                {"gtin":"%s","delta":1,"idempotencyKey":"%s","reason":"a"}""".formatted(CIDER, key));
        HttpResponse<String> conflict = post("""
                {"gtin":"%s","delta":2,"idempotencyKey":"%s","reason":"b"}""".formatted(CIDER, key));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void 같은_키_동시_요청은_한_건만_기록되고_전부_200이다() throws InterruptedException {
        stock.set(CIDER, 10);
        String key = "adj-" + System.nanoTime();
        String body = """
                {"gtin":"%s","delta":3,"idempotencyKey":"%s","reason":"동시성 테스트"}""".formatted(CIDER, key);
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<HttpResponse<String>>> tasks = java.util.stream.IntStream.range(0, threads)
                .<Callable<HttpResponse<String>>>mapToObj(i -> () -> {
                    start.await();
                    return post(body);
                })
                .toList();

        List<Future<HttpResponse<String>>> futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        List<HttpResponse<String>> responses = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        List<InventoryAdjustmentResponse> parsed = responses.stream()
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.body(), InventoryAdjustmentResponse.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        assertThat(parsed.stream().map(InventoryAdjustmentResponse::txId).distinct()).hasSize(1);
        assertThat(parsed.stream().filter(r -> !r.duplicated())).hasSize(1);
        assertThat(inventoryTxRepository.findByIdempotencyKey(key)).isPresent();
        assertThat(stock.onHand(CIDER)).isEqualTo(13);
    }

    @Test
    void 키가_없으면_400() throws IOException, InterruptedException {
        HttpResponse<String> bad = post("""
                {"gtin":"%s","delta":1,"reason":"x"}""".formatted(CIDER));
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
