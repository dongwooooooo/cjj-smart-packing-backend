package com.awesome.backend.domain.product;

/** 상품 레코드의 출처 (docs/03-erd.md §2 product.source). */
public enum ProductSource {
    /** 코리안넷 마스터에서 복사됨. */
    MASTER,
    /** 미등록 바코드를 수기 등록한 임시 마스터 (1-2). */
    MANUAL
}
