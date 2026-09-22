package com.awesome.backend.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.awesome.backend.inbound.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

/**
 * 실제 Lambda 를 한 번 부른다 — 이벤트 형식·인증·응답 해석이 실함수와 맞는지 확인하는 용도다.
 *
 * <p>평소 빌드에서는 돌지 않는다. AWS 자격증명이 있는 환경에서
 * {@code INFERENCE_LAMBDA_FUNCTION} 과 {@code INFERENCE_API_KEY} 를 주고 실행한다:
 * <pre>
 * INFERENCE_LAMBDA_FUNCTION=logistics-dimension-api:live INFERENCE_API_KEY=... \
 *   ./gradlew test --tests '*LambdaInferenceLiveTest'
 * </pre>
 * 사진은 합성 이미지라 치수 값 자체는 의미가 없다. 타임아웃은 콜드스타트(약 10초)를 견디게 넉넉히 둔다.
 */
@EnabledIfEnvironmentVariable(named = "INFERENCE_LAMBDA_FUNCTION", matches = ".+")
class LambdaInferenceLiveTest {

    @Test
    void 실함수를_불러_치수를_받는다() throws IOException {
        LambdaClient lambda = LambdaClient.builder()
                .region(Region.of(System.getenv().getOrDefault("INFERENCE_AWS_REGION", "ap-northeast-2")))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(60))
                        .retryStrategy(b -> b.maxAttempts(1))
                        .build())
                .build();
        LambdaInferenceClient client = new LambdaInferenceClient(lambda,
                System.getenv("INFERENCE_LAMBDA_FUNCTION"), System.getenv("INFERENCE_API_KEY"),
                new ObjectMapper(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        List<CameraImage> images = List.of(
                new CameraImage((short) 1, syntheticJpeg(Color.ORANGE), "cam1"),
                new CameraImage((short) 2, syntheticJpeg(Color.CYAN), "cam2"),
                new CameraImage((short) 3, syntheticJpeg(Color.PINK), "cam3"));

        long started = System.nanoTime();
        InferenceResult result = client.infer(mock(Product.class), images);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("live inference: %s (%d ms)%n", result, elapsedMs);

        assertThat(result.failed()).as("failReason=%s", result.failReason()).isFalse();
        assertThat(result.widthCm()).isNotNull();
        assertThat(result.lengthCm()).isNotNull();
        assertThat(result.heightCm()).isNotNull();
        assertThat(result.confidence()).isNull();
    }

    /** 모델 입력(288×512)에 맞춘 단색 배경 + 상자 하나. 디코드만 되면 된다. */
    private static byte[] syntheticJpeg(Color color) throws IOException {
        BufferedImage image = new BufferedImage(512, 288, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, 512, 288);
        g.setColor(color);
        g.fillRect(180, 80, 150, 130);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
