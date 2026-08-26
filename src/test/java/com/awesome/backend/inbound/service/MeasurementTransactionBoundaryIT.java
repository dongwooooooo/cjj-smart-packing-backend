package com.awesome.backend.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inbound.repository.MeasurementSessionRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 추론 호출이 트랜잭션 밖에서 일어나는지 본다. Lambda 는 콜드 스타트에 10초까지 걸려서,
 * 트랜잭션 안에서 부르면 그동안 DB 커넥션을 잡는다.
 *
 * <p>테스트 트랜잭션을 쓰지 않는다 — 쓰면 서비스가 그 트랜잭션에 참여해 경계가 안 보인다.
 */
@SpringBootTest
@Testcontainers
class MeasurementTransactionBoundaryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    static final AtomicBoolean TX_ACTIVE_DURING_INFERENCE = new AtomicBoolean(true);

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        InferenceClient probeInferenceClient() {
            return (product, images) -> {
                TX_ACTIVE_DURING_INFERENCE.set(
                        TransactionSynchronizationManager.isActualTransactionActive());
                return InferenceResult.failed("PROBE");
            };
        }
    }

    @Autowired MeasurementService measurementService;
    @Autowired ProductRepository productRepository;
    @Autowired MeasurementSessionRepository sessionRepository;

    @Test
    void 추론은_트랜잭션_밖에서_호출된다() {
        Long productId = productRepository.findAll().stream()
                .map(p -> p.id()).findFirst().orElseThrow();

        measurementService.measure(productId);

        assertThat(TX_ACTIVE_DURING_INFERENCE.get()).isFalse();
    }

    @Test
    void 추론이_끝난_뒤_세션은_저장된다() {
        Long productId = productRepository.findAll().stream()
                .map(p -> p.id()).findFirst().orElseThrow();

        var response = measurementService.measure(productId);

        assertThat(sessionRepository.findById(response.sessionId())).isPresent();
        List<?> all = sessionRepository.findAll();
        assertThat(all).isNotEmpty();
    }
}
