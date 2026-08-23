package com.awesome.backend.outbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "box_type")
public class BoxType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "inner_width_cm", nullable = false)
    private BigDecimal innerWidthCm;

    @Column(name = "inner_length_cm", nullable = false)
    private BigDecimal innerLengthCm;

    @Column(name = "inner_height_cm", nullable = false)
    private BigDecimal innerHeightCm;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    protected BoxType() {
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public BigDecimal innerWidthCm() {
        return innerWidthCm;
    }

    public BigDecimal innerLengthCm() {
        return innerLengthCm;
    }

    public BigDecimal innerHeightCm() {
        return innerHeightCm;
    }

    public int stockQty() {
        return stockQty;
    }

    public void decreaseStock() {
        this.stockQty -= 1;
    }
}
