package com.awesome.backend.outbound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "shipment_item")
public class ShipmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int qty;

    protected ShipmentItem() {
    }

    public ShipmentItem(Long shipmentId, Long productId, int qty) {
        this.shipmentId = shipmentId;
        this.productId = productId;
        this.qty = qty;
    }

    public Long productId() {
        return productId;
    }

    public int qty() {
        return qty;
    }
}
