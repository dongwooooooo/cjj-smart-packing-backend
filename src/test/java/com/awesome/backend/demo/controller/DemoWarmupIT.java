package com.awesome.backend.demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.inbound.service.InferenceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 워밍은 부가 효과다 — 추론이 죽어 있어도 리셋 자체는 성공해야 한다.
 * 시연 직전에 리셋이 막히면 아무것도 못 한다.
 */
@SpringBootTest
@Testcontainers
class DemoWarmupIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";

    static final java.util.concurrent.atomic.AtomicBoolean TX_ACTIVE_DURING_WARMUP =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    @TestConfiguration
    static class BrokenInferenceConfig {
        @Bean
        @Primary
        InferenceClient brokenInferenceClient() {
            return (product, images) -> {
                TX_ACTIVE_DURING_WARMUP.set(org.springframework.transaction.support
                        .TransactionSynchronizationManager.isActualTransactionActive());
                throw new IllegalStateException("추론 서버 없음");
            };
        }
    }

    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 추론이_죽어_있어도_리셋은_성공하고_요약에_실패가_남는다() throws Exception {
        mvc.perform(post(RESET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary", containsString("추론 워밍: 실패")));
    }

    @Test
    void 워밍은_리셋_트랜잭션_밖에서_호출된다() throws Exception {
        // 안에서 부르면 콜드 스타트 10초 동안 DB 커넥션을 잡는다.
        mvc.perform(post(RESET)).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(TX_ACTIVE_DURING_WARMUP.get()).isFalse();
    }
}
