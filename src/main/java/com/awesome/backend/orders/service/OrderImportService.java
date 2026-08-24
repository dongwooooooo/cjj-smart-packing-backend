package com.awesome.backend.orders.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고지시 접수 서비스.
 *
 * <p>U2 범위는 주문별 검증까지다 — 1층(배치 전체 거부)을 통과하면 주문별로 걸러내고,
 * 거부 목록만 채워 돌려준다. 통과 주문은 아직 저장하지 않는다.
 * 카토나이제이션(§4), 라인 배정(§5), 토트 할당·저장(§6)은 U3 이후에 붙는다.
 */
@Service
public class OrderImportService {

    private final BatchIntakeValidator validator;
    private final OrderScreener screener;

    public OrderImportService(BatchIntakeValidator validator, OrderScreener screener) {
        this.validator = validator;
        this.screener = screener;
    }

    @Transactional
    public OrderImportResult importOrders(OrderImportCommand command) {
        validator.validate(command);
        OrderScreener.Screening screening = screener.screen(command);
        return new OrderImportResult(0, 0, 0, screening.rejected());
    }
}
