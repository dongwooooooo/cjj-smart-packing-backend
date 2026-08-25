package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoOrderQueue;
import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.orders.controller.OrdersImportRequest;
import com.awesome.backend.orders.service.OrderImportResult;
import com.awesome.backend.orders.service.OrderImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대기열에서 배치를 하나씩 꺼내 접수한다 (명세 §5).
 *
 * <p>접수와 투입 표시가 한 트랜잭션이다. 접수가 실패하면 표시도 함께 되돌아가
 * 그 배치가 대기열에 남는다 — 원인을 고친 뒤 같은 배치를 다시 넣을 수 있다.
 */
@Service
public class DemoOrderFeeder {

    private final DemoOrderQueueRepository queueRepository;
    private final OrderImportService orderImportService;
    private final Validator validator;
    /**
     * 배치 본문에 주문 시각이 들어 있어 날짜 모듈이 필요하다. findAndAddModules로
     * 클래스패스에 있는 모듈을 그대로 붙인다 — 접수 경로와 같은 형식으로 읽으려는 것이다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    public DemoOrderFeeder(DemoOrderQueueRepository queueRepository,
                           OrderImportService orderImportService, Validator validator) {
        this.queueRepository = queueRepository;
        this.orderImportService = orderImportService;
        this.validator = validator;
    }

    /** 대기열이 비어 있으면 빈 값. 그 경우 호출자는 더 넣을 게 없다고 보면 된다. */
    @Transactional
    public Optional<DemoNextResult> feedNext() {
        Optional<DemoOrderQueue> queued = queueRepository.findFirstByReleasedAtIsNullOrderBySeqAsc();
        if (queued.isEmpty()) {
            return Optional.empty();
        }
        DemoOrderQueue batch = queued.get();
        OrdersImportRequest request = parse(batch);
        OrderImportResult result = orderImportService.importOrders(request.toCommand());
        batch.release();
        // 조회 전에 변경분이 flush되므로 방금 꺼낸 배치는 이미 빠진 수가 나온다
        int remaining = queueRepository.countByReleasedAtIsNull();
        return Optional.of(DemoNextResult.of(batch.seq(), remaining, request.batchId(), result));
    }

    /**
     * 큐에 담긴 본문은 화면이 아니라 파일에서 왔으므로 컨트롤러의 형식 검증을 거치지 않는다.
     * 같은 제약을 여기서 한 번 돌려, 파일이 잘못됐을 때 접수 도중이 아니라 여기서 잡는다.
     */
    private OrdersImportRequest parse(DemoOrderQueue batch) {
        OrdersImportRequest request;
        try {
            request = objectMapper.readValue(batch.batchJson(), OrdersImportRequest.class);
        } catch (Exception e) {
            throw new DemoDataException(
                    "대기열의 배치를 읽지 못했습니다 — seq " + batch.seq() + ": " + e.getMessage(), e);
        }
        Set<ConstraintViolation<OrdersImportRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new DemoDataException(
                    "대기열의 배치 형식이 잘못됐습니다 — seq " + batch.seq() + ": " + detail);
        }
        return request;
    }
}
