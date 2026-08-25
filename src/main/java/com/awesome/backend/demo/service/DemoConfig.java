package com.awesome.backend.demo.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 시연 설정값 등록. */
@Configuration
@EnableConfigurationProperties(DemoDataProperties.class)
public class DemoConfig {
}
