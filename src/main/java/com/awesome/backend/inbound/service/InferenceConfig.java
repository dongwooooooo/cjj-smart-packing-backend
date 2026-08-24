package com.awesome.backend.inbound.service;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 추론 클라이언트 선택 — {@code INFERENCE_API_URL} 이 있으면 HTTP, 없으면 mock (D-04). */
@Configuration
@EnableConfigurationProperties(InferenceProperties.class)
public class InferenceConfig {

    private static final Logger log = LoggerFactory.getLogger(InferenceConfig.class);

    @Bean
    public InferenceClient inferenceClient(InferenceProperties properties) {
        if (properties.useMock()) {
            log.info("INFERENCE_API_URL 미설정 — mock 추론으로 기동한다 (D-04).");
            return new MockInferenceClient(properties.mock());
        }

        log.info("추론 API 연동: {} (timeout {}s)", properties.apiUrl(), properties.timeoutSeconds());

        // 8초 상한은 서버 응답 계약이다 (02 §1-3). 읽기 타임아웃을 그보다 크게 두면
        // 계약보다 늦게 응답하게 되므로 여기서 맞춰 끊는다.
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.apiUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();

        return new HttpInferenceClient(restClient);
    }
}
