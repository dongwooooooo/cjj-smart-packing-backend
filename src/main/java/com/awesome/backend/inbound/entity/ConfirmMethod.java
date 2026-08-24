package com.awesome.backend.inbound.entity;

/** 확정 방식 (docs/02-api-spec.md 1-4 의 method). */
public enum ConfirmMethod {
    /** 추론값 승인. gate_passed=false 인 세션에는 사용할 수 없다 (409 GATE_NOT_PASSED). */
    APPROVE,
    /** 작업자 수기 입력. MEASURE_FAILED 세션 포함 항상 허용된다. */
    MANUAL
}
