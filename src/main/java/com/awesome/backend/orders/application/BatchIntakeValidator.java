package com.awesome.backend.orders.application;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.orders.domain.OrderRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 명세 §3 1층 중 조회가 필요한 검증. 형식 검증(필수 필드·수량·빈 배열)은
 * 요청 DTO의 bean validation이 맡고, 여기서는 기준정보·기존 접수 이력과 대조한다.
 *
 * <p>상품 마스터는 ProductCatalog 경계로만 본다 — orders는 타 도메인의 엔티티·리포지토리를
 * 직접 쓰지 않는다.
 *
 * <p>위반은 전부 배치 전체 400 VALIDATION_ERROR다. 미등록 GTIN을 건별 거부가 아니라
 * 전체 거부로 두는 건 시연 데이터를 통제하기 때문이며, 상용 API와의 의도적 차이다 (§3).
 */
@Component
public class BatchIntakeValidator {

    private final ProductCatalog productCatalog;
    private final OrderRepository orderRepository;

    public BatchIntakeValidator(ProductCatalog productCatalog, OrderRepository orderRepository) {
        this.productCatalog = productCatalog;
        this.orderRepository = orderRepository;
    }

    public void validate(OrderImportCommand command) {
        rejectDuplicateReceiptNos(command);
        rejectAlreadyImported(command);
        rejectUnknownGtins(command);
    }

    /** 배치 안에서 주문번호가 겹치면, 저장 단계까지 가봐야 UNIQUE 제약에 걸린다. 접수에서 끊는다. */
    private void rejectDuplicateReceiptNos(OrderImportCommand command) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (OrderImportCommand.OrderLine order : command.orders()) {
            if (!seen.add(order.receiptNo())) {
                duplicates.add(order.receiptNo());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "한 배치 안에 같은 주문번호가 두 번 이상 들어왔습니다.",
                    Map.of("duplicateReceiptNos", List.copyOf(duplicates)));
        }
    }

    /**
     * 같은 배치를 다시 보낸 경우 (네트워크 재시도 포함). 별도 멱등 키를 두지 않고
     * 주문번호 UNIQUE 제약으로 막는다는 §2 결정을 접수 시점으로 앞당긴 검사다.
     */
    private void rejectAlreadyImported(OrderImportCommand command) {
        List<String> receiptNos = command.orders().stream()
                .map(OrderImportCommand.OrderLine::receiptNo)
                .distinct()
                .toList();
        List<String> existing = orderRepository.findExistingReceiptNos(receiptNos);
        if (!existing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "이미 접수된 주문번호가 포함돼 있습니다.",
                    Map.of("existingReceiptNos", existing));
        }
    }

    private void rejectUnknownGtins(OrderImportCommand command) {
        List<String> gtins = command.orders().stream()
                .flatMap(order -> order.items().stream())
                .map(OrderImportCommand.ItemLine::gtin)
                .distinct()
                .toList();
        Set<String> known = Set.copyOf(productCatalog.findKnownGtins(gtins));
        List<String> unknown = gtins.stream().filter(gtin -> !known.contains(gtin)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "상품 마스터에 없는 바코드가 포함돼 있습니다.",
                    Map.of("unknownGtins", unknown));
        }
    }
}
