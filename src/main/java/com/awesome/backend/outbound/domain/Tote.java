package com.awesome.backend.outbound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tote")
public class Tote {

    public enum Status { IDLE, ASSIGNED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    protected Tote() {
    }

    public Long id() {
        return id;
    }

    public String barcode() {
        return barcode;
    }

    public Status status() {
        return status;
    }

    public void assign() {
        this.status = Status.ASSIGNED;
    }

    public void release() {
        this.status = Status.IDLE;
    }
}
