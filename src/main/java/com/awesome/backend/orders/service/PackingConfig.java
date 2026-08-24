package com.awesome.backend.orders.service;

import com.awesome.backend.orders.packing.BlockFactory;
import com.awesome.backend.orders.packing.Cartonizer;
import com.awesome.backend.orders.packing.PackingEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 편성 엔진 빈 구성. 엔진·팩토리는 순수 클래스라 단위 테스트에서 직접 생성하고,
 * 애플리케이션에서는 설정값을 물려 여기서 한 번만 만든다.
 */
@Configuration
@EnableConfigurationProperties(PackingProperties.class)
public class PackingConfig {

    @Bean
    public PackingEngine packingEngine(PackingProperties properties) {
        return new PackingEngine(properties.marginCm());
    }

    @Bean
    public BlockFactory blockFactory(PackingProperties properties) {
        return new BlockFactory(properties.fillerThicknessCm());
    }

    @Bean
    public Cartonizer cartonizer(PackingEngine packingEngine) {
        return new Cartonizer(packingEngine);
    }
}
