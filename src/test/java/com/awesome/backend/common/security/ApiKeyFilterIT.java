package com.awesome.backend.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * API 앞단 열쇠 검사 (D-26).
 *
 * <p>시연 서버는 인터넷에 열려 있고 백엔드에는 로그인이 없다 (02 §0). 화면만 열쇠를 쥐고,
 * 그 밖의 호출은 어느 경로든 막힌다 — 관리자 경로도 예외가 아니다.
 */
@SpringBootTest
@Testcontainers
class ApiKeyFilterIT {

    private static final String KEY = "test-demo-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    /**
     * 필터를 직접 얹는다. 설정으로 켜면 이 클래스만의 컨텍스트가 새로 뜨고, 그 컨텍스트가
     * 다른 테스트에 재사용될 때 전부 401 이 된다.
     */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ApiKeyFilter(KEY))
                .build();
    }

    @Test
    void 열쇠가_없으면_막는다() throws Exception {
        mvc.perform(get("/api/v1/box-types"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 열쇠가_틀리면_막는다() throws Exception {
        mvc.perform(get("/api/v1/box-types").header(ApiKeyFilter.HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_경로도_예외가_아니다() throws Exception {
        // 시연 데이터를 통째로 갈아엎는 경로다. 밖에서 부를 수 있으면 시연 중에 날아간다.
        mvc.perform(post("/api/v1/admin/demo/reset"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 열쇠가_맞으면_통과한다() throws Exception {
        mvc.perform(get("/api/v1/box-types").header(ApiKeyFilter.HEADER, KEY))
                .andExpect(status().isOk());
    }

    @Test
    void 헬스체크는_열쇠_없이도_열려_있다() throws Exception {
        // 배포 스크립트와 컨테이너가 기동 확인에 쓴다. 상태값 말고는 아무것도 돌려주지 않는다.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
