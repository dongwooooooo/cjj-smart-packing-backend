package com.awesome.backend.support;

import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.inventory.service.AvailableStockQuery;
import com.awesome.backend.inventory.service.StockMovementRecorder;
import org.springframework.boot.test.context.TestComponent;

/**
 * 테스트용 재고 설정·조회. product.stock_qty 를 직접 만지지 않고 원장 창구만 쓴다 —
 * 원장 기반 재고(D-L3)에서는 컬럼을 읽지 않기 때문이다.
 */
@TestComponent
public class StockTestSupport {

    private final StockMovementRecorder recorder;
    private final AvailableStockQuery query;
    private final ProductRepository productRepository;

    public StockTestSupport(StockMovementRecorder recorder, AvailableStockQuery query,
                            ProductRepository productRepository) {
        this.recorder = recorder;
        this.query = query;
        this.productRepository = productRepository;
    }

    /** 실재고를 정확히 qty 로 맞춘다 (차이만큼 조정 원장 추가). */
    public void set(String gtin, int qty) {
        int delta = qty - query.onHandQty(gtin);
        if (delta != 0) {
            recorder.adjust(gtin, delta, "internal-" + java.util.UUID.randomUUID(), "test");
        }
    }

    public int onHand(String gtin) {
        return query.onHandQty(gtin);
    }

    public void setByProductId(Long productId, int qty) {
        set(gtinOf(productId), qty);
    }

    public int onHandByProductId(Long productId) {
        return onHand(gtinOf(productId));
    }

    private String gtinOf(Long productId) {
        return productRepository.findGtinById(productId).orElseThrow();
    }
}
