package com.awesome.backend.domain.product;

/** 코리안넷 표준 분류의 계층 (docs/03-erd.md §2). */
public enum CategoryLevel {
    /** 대분류 — parent_code 가 NULL 인 행. */
    LARGE,
    /** 중분류 — 다른 테이블이 참조하는 것은 항상 이쪽 코드다. */
    MEDIUM
}
