package com.awesome.backend.inbound.scan;

/**
 * 바코드 스캔 3분기 판정 (docs/02-api-spec.md 1-1).
 * 판정 기준은 마스터 존재 여부와 product.dim_status 두 가지다.
 */
public enum ScanJudgment {
    /** 마스터 O + 치수 O — 촬영을 건너뛰고 수량 입고로 간다. */
    REGISTERED,
    /** 마스터 O + 치수 X — 촬영 플로우로 진입한다. 이 시점에 product 가 생성된다. */
    NEW,
    /** 마스터 X — 수기 등록(1-2)으로 유도한다. 스캐너 오독 방어 경로. */
    UNKNOWN
}
