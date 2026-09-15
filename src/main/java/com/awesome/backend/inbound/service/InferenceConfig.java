package com.awesome.backend.inbound.service;

import com.awesome.backend.demo.repository.DemoProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

/** 추론 클라이언트 선택 — {@code INFERENCE_LAMBDA_FUNCTION} 이 있으면 Lambda, 없으면 mock (D-04, D-24). */
@Configuration
@EnableConfigurationProperties(InferenceProperties.class)
public class InferenceConfig {

    private static final Logger log = LoggerFactory.getLogger(InferenceConfig.class);

    /** 함수 연결 자체는 빨라야 한다 — 느린 건 콜드스타트 쪽이고 그건 apiCallTimeout 이 잡는다. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public InferenceClient inferenceClient(InferenceProperties properties,
                                           DemoProductRepository demoProducts) {
        if (properties.useMock()) {
            log.info("INFERENCE_LAMBDA_FUNCTION 미설정 — mock 추론으로 기동한다 (D-04).");
            return new MockInferenceClient(properties.mock(), demoProducts);
        }

        log.info("추론 Lambda 연동: {} ({}, timeout {}s)", properties.lambdaFunction(),
                properties.region(), properties.timeoutSeconds());

        // 8초 상한은 서버 응답 계약이다 (02 §1-3). SDK 재시도까지 포함한 전체 시간을 여기서 끊는다 —
        // 재시도를 허용하면 콜드스타트 한 번이 두 번이 된다.
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());
        var builder = LambdaClient.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(CONNECT_TIMEOUT)
                        .socketTimeout(timeout))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(timeout)
                        .apiCallAttemptTimeout(timeout)
                        .retryStrategy(b -> b.maxAttempts(1))
                        .build());
        // 로컬 측정: Lambda 컨테이너 이미지를 RIE 로 띄우고 여기로 돌린다. RIE 는 서명을 검증하지
        // 않지만 SDK 는 자격증명이 있어야 요청을 만들므로 더미를 넣는다.
        if (properties.hasEndpointOverride()) {
            log.info("추론 엔드포인트 오버라이드: {}", properties.endpointOverride());
            builder.endpointOverride(URI.create(properties.endpointOverride()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        LambdaClient lambda = builder.build();

        // Boot 4 는 Jackson 3 를 자동 구성하고 Jackson 2 ObjectMapper 빈은 없다 — 데모 로더와 같은 방식으로
        // 직접 만든다. 이벤트 JSON 은 HTTP 응답과 무관하니 앱 설정을 공유할 이유도 없다.
        return new LambdaInferenceClient(lambda, properties.lambdaFunction(), properties.apiKey(),
                new ObjectMapper());
    }
}
