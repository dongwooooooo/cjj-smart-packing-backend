package com.awesome.backend.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "region")
public class Region {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    protected Region() {
    }

    public String code() {
        return code;
    }
}
