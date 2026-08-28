package com.awesome.backend.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 열쇠 검사 등록 — {@code DEMO_API_KEY} 가 있을 때만 건다 (D-26). */
@Configuration
public class ApiKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyConfig.class);

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
            @Value("${demo.api-key:}") String apiKey) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();

        if (apiKey == null || apiKey.isBlank()) {
            log.info("DEMO_API_KEY 미설정 — 열쇠 검사 없이 기동한다 (개발·테스트).");
            registration.setEnabled(false);
            registration.setFilter(new ApiKeyFilter(""));
            return registration;
        }

        log.info("API 열쇠 검사를 건다 ({} 헤더). 헬스체크만 열려 있다.", ApiKeyFilter.HEADER);
        registration.setFilter(new ApiKeyFilter(apiKey));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
