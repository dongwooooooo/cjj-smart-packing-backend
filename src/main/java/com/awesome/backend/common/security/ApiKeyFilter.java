package com.awesome.backend.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 앞단 열쇠 검사 (D-26).
 *
 * <p>시연 서버가 인터넷에 열려 있고 백엔드에는 로그인이 없다 (02 §0 — 인증은 시연 범위 밖).
 * 화면은 Vercel 에서 돌고 서버 사이드에서만 이 API 를 부르므로, 그 경로에만 열쇠를 쥐어 주고
 * 나머지는 막는다. 열쇠가 없으면 어떤 경로가 있는지도 알려주지 않는다.
 *
 * <p>{@code DEMO_API_KEY} 가 비어 있으면 검사하지 않는다 — 로컬 개발과 테스트가 그대로 돈다.
 * 추론 Lambda 가 {@code X-API-Key} 를 받는 것과 같은 방식이다 (D-24).
 *
 * <p>헬스체크만 열어 둔다. 배포 스크립트와 컨테이너가 기동 확인에 쓰는데, 여기에 열쇠를 물리면
 * 배포가 자기 자신을 못 본다. 상태값 말고는 아무것도 돌려주지 않는 경로다.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    public static final String HEADER = "X-Demo-Key";

    private static final String HEALTH_PATH = "/actuator/health";

    private final String expected;

    public ApiKeyFilter(String expected) {
        this.expected = expected;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }

        // 어느 경로였는지는 남기되 응답에는 담지 않는다 — 밖에서 경로를 훑는 데 쓰이지 않게 한다.
        log.warn("열쇠 없는 요청을 막았다. {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("""
                {"code":"UNAUTHORIZED","message":"접근 권한이 없습니다."}""");
    }

    /** 길이가 달라도 같은 시간이 걸리게 비교한다 — 응답 시간으로 열쇠를 좁혀 나가지 못하게. */
    private boolean matches(String supplied) {
        byte[] a = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            diff |= (i < a.length ? a[i] : 0) ^ (i < b.length ? b[i] : 0);
        }
        return diff == 0;
    }
}
