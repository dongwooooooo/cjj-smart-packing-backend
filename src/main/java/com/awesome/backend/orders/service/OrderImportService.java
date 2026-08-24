package com.awesome.backend.orders.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고지시 접수 서비스.
 *
 * <p>U1 범위는 1층 검증까지다 — 검증을 통과하면 빈 결과를 돌려준다.
 * 주문별 검증(§3 2층), 카토나이제이션(§4), 라인 배정(§5), 토트 할당·저장(§6)은 U2 이후에 붙는다.
 */
@Service
public class OrderImportService {

    private final BatchIntakeValidator validator;

    public OrderImportService(BatchIntakeValidator validator) {
        this.validator = validator;
    }

    @Transactional
    public OrderImportResult importOrders(OrderImportCommand command) {
        validator.validate(command);
        return OrderImportResult.empty();
    }
}
