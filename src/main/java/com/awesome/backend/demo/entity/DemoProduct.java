package com.awesome.backend.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * 시연용 상품 부가정보 (명세 §3). 상품 자체는 product 테이블에 있고,
 * 여기에는 시연에서만 쓰는 두 가지가 붙는다 — 어느 풀에 속하는지, 정답 치수가 얼마인지.
 *
 * <p>정답 치수는 입고 시연의 기준값이다. 추론이 얼마나 맞았는지 비교하거나,
 * 모델 서버 없이 돌릴 때 이 값을 흔들어 추론값을 만든다.
 */
@Entity
@Table(name = "demo_product")
public class DemoProduct {

    public enum Pool { INBOUND, OUTBOUND }

    @Id
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(columnDefinition = "char(13)")
    private String gtin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Pool pool;

    @Column(name = "gt_width_cm", nullable = false)
    private BigDecimal gtWidthCm;

    @Column(name = "gt_length_cm", nullable = false)
    private BigDecimal gtLengthCm;

    @Column(name = "gt_height_cm", nullable = false)
    private BigDecimal gtHeightCm;

    @Column(name = "image_dir")
    private String imageDir;

    protected DemoProduct() {
    }

    public DemoProduct(String gtin, Pool pool, BigDecimal gtWidthCm, BigDecimal gtLengthCm,
                       BigDecimal gtHeightCm, String imageDir) {
        this.gtin = gtin;
        this.pool = pool;
        this.gtWidthCm = gtWidthCm;
        this.gtLengthCm = gtLengthCm;
        this.gtHeightCm = gtHeightCm;
        this.imageDir = imageDir;
    }

    public String gtin() {
        return gtin;
    }

    public Pool pool() {
        return pool;
    }

    public BigDecimal gtWidthCm() {
        return gtWidthCm;
    }

    public BigDecimal gtLengthCm() {
        return gtLengthCm;
    }

    public BigDecimal gtHeightCm() {
        return gtHeightCm;
    }

    public String imageDir() {
        return imageDir;
    }

    /** 런을 다시 시작하면 같은 상품의 풀·정답 치수를 새 파일 내용으로 덮는다 (§4-3 upsert). */
    public void refresh(Pool pool, BigDecimal gtWidthCm, BigDecimal gtLengthCm,
                        BigDecimal gtHeightCm, String imageDir) {
        this.pool = pool;
        this.gtWidthCm = gtWidthCm;
        this.gtLengthCm = gtLengthCm;
        this.gtHeightCm = gtHeightCm;
        this.imageDir = imageDir;
    }
}
