package com.awesome.backend.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.code().status())
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.detail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "요청 필드 검증에 실패했습니다.",
                        Map.of("fields", fields)));
    }

    // 매핑된 핸들러가 아예 없는 요청(오타 경로 등). 정적 리소스 매핑에 끝까지
    // 걸리지 않으면 Spring이 이 예외를 던지는데, 그냥 두면 catch-all(Exception)에
    // 잡혀서 진짜 서버 오류(500)와 구분이 안 된다 — 여기서 먼저 404로 끊어낸다.
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleRouteNotFound(Exception e) {
        return ResponseEntity.status(ErrorCode.ROUTE_NOT_FOUND.status())
                .body(ErrorResponse.of(ErrorCode.ROUTE_NOT_FOUND, "요청한 경로를 찾을 수 없습니다."));
    }

    // 경로는 있는데 메서드가 다른 요청(POST 전용에 GET 등). 위와 같은 이유로 여기서 끊지 않으면
    // catch-all 이 삼켜 500 이 된다 — 호출자는 경로가 틀린 줄 알고 엉뚱한 곳을 찾게 된다.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED,
                        "이 경로에서 지원하지 않는 요청 방식입니다.",
                        Map.of("method", String.valueOf(e.getMethod()),
                                "supported", String.valueOf(e.getSupportedHttpMethods()))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "서버 내부 오류가 발생했습니다."));
    }
}
