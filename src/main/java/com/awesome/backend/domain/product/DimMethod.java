package com.awesome.backend.domain.product;

/** 확정된 치수의 출처 (docs/02-api-spec.md 1-4 의 method 와 대응). */
public enum DimMethod {
    /** 추론값을 승인. */
    INFERRED,
    /** 작업자가 수기 입력. */
    MANUAL
}
