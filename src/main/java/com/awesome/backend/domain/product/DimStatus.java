package com.awesome.backend.domain.product;

/**
 * 센터 치수 보유 여부. 바코드 스캔 3분기 판정의 기준이다 (docs/03-erd.md §2).
 * 측정 진행 중 상태는 measurement_session 이 담당하므로 여기에는 없다.
 */
public enum DimStatus {
    NONE,
    CONFIRMED
}
