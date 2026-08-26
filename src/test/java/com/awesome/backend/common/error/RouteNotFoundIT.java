package com.awesome.backend.common.error;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 매핑되지 않은 라우트는 500이 아니라 404로 응답해야 한다.
 *
 * <p>{@code GlobalExceptionHandler}의 catch-all({@code @ExceptionHandler(Exception.class)})이
 * Spring이 던지는 {@code NoResourceFoundException}까지 삼켜서 진짜 서버 오류와 구분 없이
 * INTERNAL_ERROR(500)로 응답하던 버그의 회귀 테스트. {@code /api/v1/definitely-not-a-real-route}는
 * 아직 구현되지 않은(제안 단계) API라 "존재하지 않는 경로" 표본으로 계속 안전하게 쓸 수 있다.
 *
 * <p>가벼운 {@code @WebMvcTest}가 아니라 기존 통합테스트 스타일({@code DemoResetSequenceIT})을
 * 따라 {@code @SpringBootTest} + Testcontainers로 작성했다 — 이 저장소에 슬라이스 테스트
 * 선례가 없고, 문제의 원인이 됐던 {@code GlobalExceptionHandler}는 전역 {@code @RestControllerAdvice}라
 * 실제 DispatcherServlet 예외 처리 순서(정적 리소스 매핑 → NoResourceFoundException → advice)를
 * 그대로 태워보는 편이 가정을 덜 두는 검증이라고 판단했다.
 */
@SpringBootTest
@Testcontainers
class RouteNotFoundIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String UNMAPPED_ROUTE = "/api/v1/definitely-not-a-real-route";
    private static final String EXISTING_ROUTE = "/api/v1/admin/demo/status";

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    @Test
    void 매핑되지_않은_GET_경로는_404와_공통_에러_포맷으로_응답한다() throws Exception {
        mvc().perform(get(UNMAPPED_ROUTE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.ROUTE_NOT_FOUND.name()))
                .andExpect(jsonPath("$.message").value(notNullValue()));
    }

    @Test
    void 매핑되지_않은_POST_경로도_404와_공통_에러_포맷으로_응답한다() throws Exception {
        mvc().perform(post(UNMAPPED_ROUTE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.ROUTE_NOT_FOUND.name()));
    }

    @Test
    void 기존_정상_라우트는_영향받지_않는다() throws Exception {
        mvc().perform(get(EXISTING_ROUTE))
                .andExpect(status().isOk());
    }

    @Test
    void 존재하는_경로에_잘못된_메서드면_405와_공통_에러_포맷으로_응답한다() throws Exception {
        // POST 전용 경로에 GET. 여기서 500이 나오면 catch-all 이 다시 삼키고 있는 것이다.
        mvc().perform(get("/api/v1/admin/demo/reset"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code", notNullValue()));
    }
}
