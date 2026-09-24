package com.awesome.backend.inbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.inbound.controller.StockInResponse;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.service.AvailableStockQuery;
import com.awesome.backend.inventory.service.StockMovementRecorder;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수량 입고 (02 §1-5). 재고 증가의 유일한 경로다 (D-09).
 *
 * <p>stock_qty 를 직접 고치지 않고 inventory 의 {@link StockMovementRecorder} 창구를 쓴다 —
 * 재고는 원장 창구로만 바뀐다 (docs/05 §3).
 */
@Service
public class StockInService {

    private final ProductRepository productRepository;
    private final StockMovementRecorder stockMovementRecorder;
    private final AvailableStockQuery stockQuery;

    public StockInService(ProductRepository productRepository,
                          StockMovementRecorder stockMovementRecorder,
                          AvailableStockQuery stockQuery) {
        this.productRepository = productRepository;
        this.stockMovementRecorder = stockMovementRecorder;
        this.stockQuery = stockQuery;
    }

    @Transactional
    public StockInResponse stockIn(Long productId, int qty) {
        String gtin = productRepository.findGtinById(productId)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND,
                        "상품을 찾을 수 없습니다.", Map.of("productId", productId)));
        stockMovementRecorder.recordInbound(gtin, qty);
        return new StockInResponse(productId, stockQuery.onHandQty(gtin));
    }
}
