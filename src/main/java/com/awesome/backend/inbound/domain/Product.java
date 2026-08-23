package com.awesome.backend.inbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * 센터 상품 (SKU). 테이블 주인은 P1 — 이 매핑은 P2·P3이 쓰는 읽기 컬럼과
 * 재고 캐시(stock_qty)까지만 담았다. 측정·등록 관련 컬럼은 P1이 확장한다.
 * stock_qty 갱신은 inventory의 StockMovementRecorder 단일 창구로만 한다.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(nullable = false, unique = true, columnDefinition = "char(13)")
    private String gtin;

    @Column(nullable = false)
    private String name;

    @Column(name = "medium_category_code", nullable = false)
    private String mediumCategoryCode;

    @Column(name = "width_cm")
    private BigDecimal widthCm;

    @Column(name = "length_cm")
    private BigDecimal lengthCm;

    @Column(name = "height_cm")
    private BigDecimal heightCm;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @Column(name = "is_refrigerate", nullable = false)
    private boolean refrigerate;

    @Column(name = "is_fragile", nullable = false)
    private boolean fragile;

    @Column(name = "is_irregular", nullable = false)
    private boolean irregular;

    @Column(name = "dim_status", nullable = false)
    private String dimStatus;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    protected Product() {
    }

    public Long id() {
        return id;
    }

    public String gtin() {
        return gtin;
    }

    public String name() {
        return name;
    }

    public BigDecimal widthCm() {
        return widthCm;
    }

    public BigDecimal lengthCm() {
        return lengthCm;
    }

    public BigDecimal heightCm() {
        return heightCm;
    }

    public boolean fragile() {
        return fragile;
    }

    public boolean irregular() {
        return irregular;
    }

    public String dimStatus() {
        return dimStatus;
    }

    public int stockQty() {
        return stockQty;
    }

    public void changeStockQty(int stockQty) {
        this.stockQty = stockQty;
    }
}
