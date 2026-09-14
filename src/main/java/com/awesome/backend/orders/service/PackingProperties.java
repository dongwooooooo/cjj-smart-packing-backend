package com.awesome.backend.orders.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 카토나이제이션 파라미터 (명세 §4-6).
 *
 * @param marginCm          박스 각 변에서 빼는 여유 (D-11 확정값 3cm)
 * @param fillerThicknessCm 완충재 두께. 파손주의 블록은 각 변에 양쪽 두께가 더해진다.
 *                          실측 근거 없는 임의값이라 설정으로 뺐다 — 리허설 때 조정한다.
 * @param boardThicknessCm  골판지 판두께. 외치수 = 내치수 + 이 값×2 로 세변합·최장변을 잰다.
 *                          실측 근거 없는 임의값이라 설정으로 뺐다.
 * @param carrier           택배사 접수 한도
 * @param weightCheck       출고 무게 검수 허용 오차
 */
@ConfigurationProperties(prefix = "packing")
public record PackingProperties(
        double marginCm,
        double fillerThicknessCm,
        @DefaultValue("0.5") double boardThicknessCm,
        @DefaultValue Carrier carrier,
        @DefaultValue WeightCheck weightCheck) {

    /**
     * 넘으면 그 배송단위를 만들 수 없는 하드 제약. 기본값은 요금 구간표 최상단과 맞춘다.
     */
    public record Carrier(
            @DefaultValue("160.0") double maxSumCm,
            @DefaultValue("100.0") double maxLongestCm,
            @DefaultValue("20.0") double maxWeightKg) {
    }

    /**
     * 포장완료 때 잰 무게와 예상 무게의 허용 오차. 두 값 다 근거 없는 잠정값이다.
     *
     * @param minToleranceKg 절대 오차 하한 — 예상 무게가 작을 때 비율만으로는 너무 빡빡해진다
     * @param toleranceRatio 예상 무게 대비 비율
     */
    public record WeightCheck(
            @DefaultValue("0.1") double minToleranceKg,
            @DefaultValue("0.03") double toleranceRatio) {

        /** 허용 오차 = max(절대 하한, 예상 무게 × 비율). */
        public double toleranceKg(double expectedKg) {
            return Math.max(minToleranceKg, expectedKg * toleranceRatio);
        }
    }
}
