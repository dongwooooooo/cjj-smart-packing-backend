package com.awesome.backend.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 출고지시 대기열 한 칸 (명세 §3·§5). 배치 하나를 접수 요청 본문 그대로 담아둔다.
 *
 * <p>시연 중에는 배치를 한꺼번에 밀어넣지 않고 여기서 하나씩 꺼내 접수한다.
 * 꺼낸 칸은 지우지 않고 released_at만 남긴다 — 무엇이 나갔고 무엇이 남았는지 보여야 한다.
 * 대기열 자체는 리셋 때 비워진다.
 */
@Entity
@Table(name = "demo_order_queue")
public class DemoOrderQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int seq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "batch_json", nullable = false)
    private String batchJson;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    protected DemoOrderQueue() {
    }

    public DemoOrderQueue(int seq, String batchJson) {
        this.seq = seq;
        this.batchJson = batchJson;
    }

    public Long id() {
        return id;
    }

    public int seq() {
        return seq;
    }

    public String batchJson() {
        return batchJson;
    }

    public LocalDateTime releasedAt() {
        return releasedAt;
    }

    public void release() {
        this.releasedAt = LocalDateTime.now();
    }
}
