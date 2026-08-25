package com.awesome.backend.inbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.controller.StockInResponse;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.service.StockMovementRecorder;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수량 입고 (02 §1-5). 재고 증가의 유일한 경로다 (D-09).
 *
 * <p>stock_qty 를 직접 고치지 않고 inventory 의 {@link StockMovementRecorder} 창구를 쓴다 —
 * 장부(inventory_tx) 기록과 캐시 갱신이 한 트랜잭션으로 묶여야 하기 때문이다 (docs/05 §3).
 */
@Service
public class StockInService {

    private final ProductRepository productRepository;
    private final StockMovementRecorder stockMovementRecorder;

    public StockInService(ProductRepository productRepository,
                          StockMovementRecorder stockMovementRecorder) {
        this.productRepository = productRepository;
        this.stockMovementRecorder = stockMovementRecorder;
    }

    @Transactional
    public StockInResponse stockIn(Long productId, int qty) {
        // 창구가 gtin 을 받아서 변환이 필요하다. 엔티티가 아니라 gtin 값만 읽는 것이 중요한데,
        // 여기서 Product 를 로드해두면 창구의 SELECT FOR UPDATE 가 잠금 전 인스턴스를 돌려줘
        // 동시 입고의 lost update 를 못 막는다 (ProductRepository#findGtinById 주석).
        String gtin = productRepository.findGtinById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));

        stockMovementRecorder.recordInbound(gtin, qty);

        // 창구가 잠그고 갱신한 인스턴스를 그대로 돌려받는다(같은 영속성 컨텍스트).
        int stockQty = productRepository.findById(productId).orElseThrow().stockQty();
        return new StockInResponse(productId, stockQty);
    }
}
