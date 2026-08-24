package com.awesome.backend.inbound.application;

import java.util.List;

/**
 * 상품 마스터 조회 경계. 타 도메인(P3 orders 등)은 Product 엔티티·리포지토리를 직접 보지 않고
 * 이 인터페이스만 의존한다.
 *
 * <p>읽기 전용이다 — 상품 쓰기는 P1 전용 계약이라 여기에 쓰기 메서드를 두지 않는다.
 */
public interface ProductCatalog {

    /** 넘긴 바코드 중 상품 마스터에 있는 것만 돌려준다. 배치 접수의 미등록 GTIN 검사가 호출한다. */
    List<String> findKnownGtins(List<String> gtins);
}
