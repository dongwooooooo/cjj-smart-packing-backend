package com.awesome.backend.orders.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고지시 접수 서비스 (명세 §1 처리 플로우).
 *
 * <p>1층 검증 → 주문별 심사(지역·라인·재고·편성) → 통과 주문 저장 순서다.
 * 배치 전체가 한 트랜잭션이라, 거부 주문을 빼는 건 트랜잭션 안에서의 분기이지
 * 부분 커밋이 아니다 (§6).
 *
 * <p>토트 할당과 상태 전이는 U4에서 붙는다.
 */
@Service
public class OrderImportService {

    private final BatchIntakeValidator validator;
    private final PackingPlanner packingPlanner;
    private final OrderScreener screener;
    private final OrderImportWriter writer;

    public OrderImportService(BatchIntakeValidator validator, PackingPlanner packingPlanner,
                              OrderScreener screener, OrderImportWriter writer) {
        this.validator = validator;
        this.packingPlanner = packingPlanner;
        this.screener = screener;
        this.writer = writer;
    }

    @Transactional
    public OrderImportResult importOrders(OrderImportCommand command) {
        validator.validate(command);
        PackingPlanner.Plans plans = packingPlanner.prepare(command);
        OrderScreener.Screening screening = screener.screen(command, plans);
        OrderImportWriter.Written written = writer.write(command.batchId(), screening.accepted(), plans);
        return new OrderImportResult(written.orders(), written.shipments(), written.splitOrders(),
                screening.rejected());
    }
}
