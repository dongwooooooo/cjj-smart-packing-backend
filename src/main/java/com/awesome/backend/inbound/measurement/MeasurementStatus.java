package com.awesome.backend.inbound.measurement;

/**
 * 측정 세션 상태 (docs/03-erd.md §3).
 *
 * <pre>
 * (촬영 API 호출) ──성공──▶ INFERRED ──승인/수기──▶ CONFIRMED
 *               └─8초 타임아웃──▶ MEASURE_FAILED ──수기──▶ CONFIRMED
 * 재촬영 = 기존 세션 DISCARDED 처리 후 새 세션 생성
 * </pre>
 *
 * <p>추론이 동기 응답이라 중간 상태(INFERRING 등)는 두지 않는다.
 */
public enum MeasurementStatus {
    /** 추론 성공. 확정 대기. */
    INFERRED,
    /** 타임아웃·실패. 에러가 아니라 응답의 status 값이다. 수기 확정은 여전히 가능. */
    MEASURE_FAILED,
    /** 확정 완료. */
    CONFIRMED,
    /** 재촬영으로 폐기됨. */
    DISCARDED
}
