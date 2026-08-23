package com.awesome.backend.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "line")
public class Line {

    public enum Status { ACTIVE, PAUSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "region_code", nullable = false)
    private String regionCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    protected Line() {
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String regionCode() {
        return regionCode;
    }

    public Status status() {
        return status;
    }
}
