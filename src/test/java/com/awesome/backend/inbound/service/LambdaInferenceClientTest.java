package com.awesome.backend.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.awesome.backend.inbound.entity.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Lambda 추론 호출 (D-24). 이벤트 조립과 응답 해석만 본다 — 실제 함수는 부르지 않는다.
 * 실호출은 {@code LambdaInferenceLiveTest} 가 환경변수가 있을 때만 수행한다.
 */
class LambdaInferenceClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LambdaClient lambda = mock(LambdaClient.class);
    private final LambdaInferenceClient client =
            new LambdaInferenceClient(lambda, "logistics-dimension-api:live", "secret", JSON);

    private static Product product() {
        return mock(Product.class);
    }

    private static List<CameraImage> threeImages() {
        return List.of(
                new CameraImage((short) 3, "C".getBytes(StandardCharsets.UTF_8), "/files/m/g/cam3.jpg"),
                new CameraImage((short) 1, "A".getBytes(StandardCharsets.UTF_8), "/files/m/g/cam1.jpg"),
                new CameraImage((short) 2, "B".getBytes(StandardCharsets.UTF_8), "/files/m/g/cam2.jpg"));
    }

    private static InvokeResponse response(int statusCode, String body) {
        String envelope = """
                {"statusCode": %d, "headers": {"content-type": "application/json"},
                 "body": %s, "isBase64Encoded": false}""".formatted(statusCode, JSON.valueToTree(body));
        return InvokeResponse.builder().statusCode(200).payload(SdkBytes.fromUtf8String(envelope)).build();
    }

    @Test
    void 이벤트는_API_Gateway_v2_형식이고_사진은_카메라_순서로_multipart에_실린다() throws Exception {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(response(200,
                "{\"length\": 9.7, \"width\": 12.34, \"height\": 18.4, \"unit\": \"cm\","
                        + " \"views_used\": 3, \"warnings\": [], \"elapsed_ms\": 310}"));

        client.infer(product(), threeImages());

        ArgumentCaptor<InvokeRequest> captor = ArgumentCaptor.forClass(InvokeRequest.class);
        org.mockito.Mockito.verify(lambda).invoke(captor.capture());
        InvokeRequest request = captor.getValue();
        assertThat(request.functionName()).isEqualTo("logistics-dimension-api:live");

        JsonNode event = JSON.readTree(request.payload().asUtf8String());
        assertThat(event.path("version").asText()).isEqualTo("2.0");
        assertThat(event.path("rawPath").asText()).isEqualTo("/predict");
        assertThat(event.path("requestContext").path("http").path("method").asText()).isEqualTo("POST");
        assertThat(event.path("isBase64Encoded").asBoolean()).isTrue();
        assertThat(event.path("headers").path("x-api-key").asText()).isEqualTo("secret");
        assertThat(event.path("headers").path("content-type").asText()).startsWith("multipart/form-data; boundary=");

        String body = new String(Base64.getDecoder().decode(event.path("body").asText()), StandardCharsets.UTF_8);
        assertThat(body).contains("name=\"views\"\r\n\r\n1-1,1-2,1-3\r\n");
        // 카메라 번호 순으로 정렬돼 실려야 views 와 슬롯이 맞는다.
        assertThat(body.indexOf("filename=\"cam1.jpg\"")).isLessThan(body.indexOf("filename=\"cam2.jpg\""));
        assertThat(body.indexOf("filename=\"cam2.jpg\"")).isLessThan(body.indexOf("filename=\"cam3.jpg\""));
        assertThat(body).contains("Content-Type: image/jpeg\r\n\r\nA\r\n");
    }

    @Test
    void 응답_치수를_소수_1자리로_옮기고_confidence는_없다() {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(response(200,
                "{\"length\": 9.74, \"width\": 12.35, \"height\": 18.4, \"unit\": \"cm\","
                        + " \"views_used\": 3, \"warnings\": [\"슬롯 경고\"], \"elapsed_ms\": 310}"));

        InferenceResult result = client.infer(product(), threeImages());

        assertThat(result.failed()).isFalse();
        assertThat(result.widthCm()).isEqualByComparingTo(new BigDecimal("12.4"));
        assertThat(result.lengthCm()).isEqualByComparingTo(new BigDecimal("9.7"));
        assertThat(result.heightCm()).isEqualByComparingTo(new BigDecimal("18.4"));
        assertThat(result.confidence()).isNull();
    }

    @Test
    void base64_응답_본문도_읽는다() {
        String body = "{\"length\": 1.0, \"width\": 2.0, \"height\": 3.0}";
        String envelope = """
                {"statusCode": 200, "body": "%s", "isBase64Encoded": true}"""
                .formatted(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(
                InvokeResponse.builder().statusCode(200).payload(SdkBytes.fromUtf8String(envelope)).build());

        InferenceResult result = client.infer(product(), threeImages());

        assertThat(result.failed()).isFalse();
        assertThat(result.widthCm()).isEqualByComparingTo(new BigDecimal("2.0"));
    }

    @Test
    void 사진이_3장이_아니면_호출하지_않고_NO_IMAGES() {
        InferenceResult result = client.infer(product(), List.of());

        assertThat(result.failReason()).isEqualTo("NO_IMAGES");
        org.mockito.Mockito.verifyNoInteractions(lambda);
    }

    @Test
    void 함수가_200이_아니면_LAMBDA_ERROR() {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(response(401,
                "{\"detail\": \"X-API-Key 헤더가 없거나 틀렸습니다\"}"));

        assertThat(client.infer(product(), threeImages()).failReason()).isEqualTo("LAMBDA_ERROR");
    }

    @Test
    void 함수_실행_오류는_LAMBDA_ERROR() {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(InvokeResponse.builder()
                .statusCode(200).functionError("Unhandled")
                .payload(SdkBytes.fromUtf8String("{\"errorMessage\": \"boom\"}")).build());

        assertThat(client.infer(product(), threeImages()).failReason()).isEqualTo("LAMBDA_ERROR");
    }

    @Test
    void 치수가_빠진_응답은_INVALID_RESPONSE() {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(response(200,
                "{\"length\": 9.7, \"width\": null, \"height\": 18.4}"));

        assertThat(client.infer(product(), threeImages()).failReason()).isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void SDK_타임아웃은_TIMEOUT() {
        when(lambda.invoke(any(InvokeRequest.class)))
                .thenThrow(ApiCallTimeoutException.builder().message("timed out").build());

        assertThat(client.infer(product(), threeImages()).failReason()).isEqualTo("TIMEOUT");
    }
}
