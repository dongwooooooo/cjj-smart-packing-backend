package com.awesome.backend.inbound.service;

import com.awesome.backend.demo.service.DemoDataProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 촬영 이미지 정적 서빙 — 1-3·1-6 응답의 {@code /files/m/...} URL 을 실제 파일로 잇는다.
 *
 * <p>위치는 {@link MeasurementImageSource} 가 읽는 디렉토리와 같다. 둘이 어긋나면 응답 URL 이
 * 404 가 되므로 루트 계산을 한 곳({@link MeasurementImageSource#imagesRoot})에 둔다.
 */
@Configuration
public class MeasurementImageResourceConfig implements WebMvcConfigurer {

    private final DemoDataProperties properties;

    public MeasurementImageResourceConfig(DemoDataProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = MeasurementImageSource.imagesRoot(properties).toUri().toString();
        registry.addResourceHandler(MeasurementImageSource.URL_PREFIX + "**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/");
    }
}
