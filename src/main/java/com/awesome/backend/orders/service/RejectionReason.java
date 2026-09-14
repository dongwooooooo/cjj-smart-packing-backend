package com.awesome.backend.orders.service;

/** 주문별 거부 사유 (명세 §3 2층, §5). */
public enum RejectionReason {

    /** regionCode가 지역 기준정보에 없음. */
    UNKNOWN_REGION,

    /** 배치 내 누적 수량이 가용재고를 넘음. 주문 단위 전체 거부 — 부분 출고 없음. */
    INSUFFICIENT_STOCK,

    /** 낱개 하나가 어떤 박스에도 안 들어감 (§4-3 선검사). U3에서 붙는다. */
    OVERSIZED_ITEM,

    /** 낱개 하나가 택배사 접수 무게 한도를 넘음 (§4-3 선검사). 나눠 담아도 해결되지 않는다. */
    OVERWEIGHT_ITEM,

    /** 배송지역에 활성 라인이 없음 (§5). U3에서 붙는다. */
    NO_ACTIVE_LINE
}
