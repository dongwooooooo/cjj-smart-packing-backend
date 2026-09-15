package com.awesome.backend.outbound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * 택배 요금 구간 기준정보 (V13). 편성이 배송단위 요금을 계산할 때 읽는다.
 *
 * <p>박스 기준정보(box_type)와 같은 성격이라 outbound 도메인에 둔다.
 */
@Entity
@Table(name = "shipping_rate_tier")
public class ShippingRateTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "max_sum_cm", nullable = false)
    private BigDecimal maxSumCm;

    @Column(name = "max_weight_kg", nullable = false)
    private BigDecimal maxWeightKg;

    /** null이면 요금 미확정 구간. */
    @Column(name = "price_krw")
    private Integer priceKrw;

    protected ShippingRateTier() {
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int rank() {
        return rank;
    }

    public BigDecimal maxSumCm() {
        return maxSumCm;
    }

    public BigDecimal maxWeightKg() {
        return maxWeightKg;
    }

    public Integer priceKrw() {
        return priceKrw;
    }
}
