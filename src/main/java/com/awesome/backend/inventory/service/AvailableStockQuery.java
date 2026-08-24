package com.awesome.backend.inventory.service;

/**
 * Soft allocation 경계 (docs/orders-import-spec.md §3).
 * 출고지시 접수의 가용재고 검사가 호출한다.
 */
public interface AvailableStockQuery {

    /** 실재고. 장부(inventory_tx) 합계의 캐시인 product.stock_qty 기준. */
    int onHandQty(String gtin);

    /** 가용재고(ATP) = 실재고 − 포장 미완료 배송단위에 약속된 수량. */
    int availableQty(String gtin);
}
