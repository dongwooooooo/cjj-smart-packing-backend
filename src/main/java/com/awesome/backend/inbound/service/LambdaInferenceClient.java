package com.awesome.backend.inbound.service;

import com.awesome.backend.inbound.entity.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Lambda 치수 추정 API 호출 (D-24, {@code INFERENCE_LAMBDA_FUNCTION}).
 *
 * <p>함수에 공개 URL 이 없어 HTTP 대신 SDK {@code Invoke} 를 쓴다. 함수 안의 FastAPI 는
 * Mangum 으로 감싸져 있어 이벤트가 API Gateway v2 HTTP 형식이어야 한다 — multipart 본문을
 * base64 로 실은 이벤트 JSON 을 여기서 조립한다.
 *
 * <p>응답에는 confidence 가 없다 (AI팀이 표본 부족으로 제외, 2026-08-24). 결과의 confidence 는
 * null 이며 게이트는 그 항목을 건너뛴다.
 *
 * <p>어떤 실패든 예외로 새지 않고 {@code MEASURE_FAILED} 로 내려보낸다 — 프론트가 HTTP 에러가
 * 아니라 상태값으로 분기하도록 계약돼 있기 때문이다 (02 §1-3).
 */
public class LambdaInferenceClient implements InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(LambdaInferenceClient.class);

    /** 모델이 요구하는 사진 수. server.py 가 3장이 아니면 400 을 돌려준다. */
    static final int CAMERA_COUNT = 3;

    static final String PREDICT_PATH = "/predict";

    private final LambdaClient lambda;
    private final String functionName;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public LambdaInferenceClient(LambdaClient lambda, String functionName, String apiKey,
                                 ObjectMapper objectMapper) {
        this.lambda = lambda;
        this.functionName = functionName;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public InferenceResult infer(Product product, List<CameraImage> images) {
        if (images.size() != CAMERA_COUNT) {
            log.warn("추론할 사진이 {}장뿐이다 — {}장이 필요하다. productId={}",
                    images.size(), CAMERA_COUNT, product.id());
            return InferenceResult.failed("NO_IMAGES");
        }

        try {
            long t0 = System.nanoTime();
            byte[] event = buildEvent(images);
            long t1 = System.nanoTime();
            InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromByteArray(event))
                    .build());
            long t2 = System.nanoTime();
            int jpegBytes = images.stream().mapToInt(i -> i.jpeg().length).sum();

            if (response.functionError() != null) {
                log.warn("Lambda 함수 오류. productId={} error={} payload={}", product.id(),
                        response.functionError(), preview(response.payload()));
                return InferenceResult.failed("LAMBDA_ERROR");
            }

            InferenceResult parsed = parse(response.payload().asUtf8String(), product);
            long t3 = System.nanoTime();
            // 측정용 구간 로그: 이벤트 조립(base64+JSON) / Invoke 왕복 / 응답 파싱
            log.info("inference.timing productId={} jpegBytes={} eventBytes={} buildMs={} invokeMs={} parseMs={}",
                    product.id(), jpegBytes, event.length, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                    (t3 - t2) / 1_000_000);
            return parsed;

        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
            // 콜드스타트(약 10초)가 8초 계약을 넘기면 여기로 온다. 시연 시간대에는 provisioned
            // concurrency 로 막는다 (D-24).
            log.warn("추론 타임아웃. productId={}", product.id(), e);
            return InferenceResult.failed("TIMEOUT");
        } catch (Exception e) {
            log.warn("추론 호출 실패. productId={}", product.id(), e);
            return InferenceResult.failed("LAMBDA_ERROR");
        }
    }

    /** API Gateway v2 HTTP 이벤트. Mangum 이 읽는 필드만 채운다. */
    byte[] buildEvent(List<CameraImage> images) throws IOException {
        String boundary = "----backend-" + UUID.randomUUID();
        byte[] body = multipart(boundary, images);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "multipart/form-data; boundary=" + boundary);
        headers.put("content-length", String.valueOf(body.length));
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-api-key", apiKey);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "2.0");
        event.put("routeKey", "POST " + PREDICT_PATH);
        event.put("rawPath", PREDICT_PATH);
        event.put("rawQueryString", "");
        event.put("headers", headers);
        event.put("requestContext", Map.of("http", Map.of(
                "method", "POST",
                "path", PREDICT_PATH,
                "protocol", "HTTP/1.1",
                "sourceIp", "127.0.0.1",
                "userAgent", "backend")));
        event.put("body", Base64.getEncoder().encodeToString(body));
        event.put("isBase64Encoded", true);

        return objectMapper.writeValueAsBytes(event);
    }

    /**
     * server.py 의 {@code /predict} 폼: {@code images} 3개 + {@code views}.
     * 슬롯은 파일명이 아니라 views 로 명시한다 — 파일명 추정은 어긋나면 경고만 남기고 넘어가기 때문이다.
     */
    private static byte[] multipart(String boundary, List<CameraImage> images) throws IOException {
        List<CameraImage> ordered = images.stream()
                .sorted(Comparator.comparingInt(CameraImage::cameraNo))
                .toList();
        String views = ordered.stream().map(CameraImage::view).collect(Collectors.joining(","));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"views\"\r\n\r\n"
                + views + "\r\n");
        for (CameraImage image : ordered) {
            write(out, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"images\"; filename=\"cam"
                    + image.cameraNo() + ".jpg\"\r\n"
                    + "Content-Type: image/jpeg\r\n\r\n");
            out.write(image.jpeg());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Mangum 응답 {@code {statusCode, body, isBase64Encoded}} 에서 치수를 꺼낸다. */
    private InferenceResult parse(String payload, Product product) throws IOException {
        JsonNode envelope = objectMapper.readTree(payload);
        int statusCode = envelope.path("statusCode").asInt(0);
        String body = envelope.path("body").asText("");
        if (envelope.path("isBase64Encoded").asBoolean(false)) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }

        if (statusCode != 200) {
            log.warn("추론 API 가 {} 를 돌려줬다. productId={} body={}", statusCode, product.id(), body);
            return InferenceResult.failed("LAMBDA_ERROR");
        }

        JsonNode result = objectMapper.readTree(body);
        BigDecimal width = dimension(result, "width");
        BigDecimal length = dimension(result, "length");
        BigDecimal height = dimension(result, "height");
        if (width == null || length == null || height == null) {
            log.warn("추론 응답에 치수가 없다. productId={} body={}", product.id(), body);
            return InferenceResult.failed("INVALID_RESPONSE");
        }

        // warnings 는 슬롯 배치 문제를 알리는 용도라 값에는 영향이 없다. 놓치지 않게 로그로만 남긴다.
        JsonNode warnings = result.path("warnings");
        if (warnings.isArray() && !warnings.isEmpty()) {
            log.warn("추론 경고. productId={} warnings={}", product.id(), warnings);
        }
        log.info("추론 완료. productId={} views_used={} elapsed_ms={}", product.id(),
                result.path("views_used").asText("?"), result.path("elapsed_ms").asText("?"));

        return InferenceResult.success(width, length, height, null);
    }

    /** 스키마가 소수 1자리(DECIMAL(5,1))라 거기 맞춰 반올림한다. */
    private static BigDecimal dimension(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.decimalValue().setScale(1, RoundingMode.HALF_UP);
    }

    private static String preview(SdkBytes payload) {
        if (payload == null) {
            return "";
        }
        String text = payload.asUtf8String();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
