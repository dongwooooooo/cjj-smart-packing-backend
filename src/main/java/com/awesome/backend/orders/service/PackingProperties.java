package com.awesome.backend.orders.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카토나이제이션 파라미터 (명세 §4-6).
 *
 * @param marginCm          박스 각 변에서 빼는 여유 (D-11 확정값 3cm)
 * @param fillerThicknessCm 완충재 두께. 파손주의 블록은 각 변에 양쪽 두께가 더해진다.
 *                          실측 근거 없는 임의값이라 설정으로 뺐다 — 리허설 때 조정한다.
 */
@ConfigurationProperties(prefix = "packing")
public record PackingProperties(double marginCm, double fillerThicknessCm) {
}
