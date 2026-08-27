package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입고 시연에서 다음에 스캔할 바코드를 하나씩 내준다 (demo/scenario.md 입고 장면).
 *
 * <p>시연장에는 바코드 스캐너가 없다. 화면의 버튼이 이 API 를 눌러 바코드를 받아 스캔
 * 칸을 채우고, 작업자는 촬영·확정만 한다.
 *
 * <p>내준 바코드는 {@code served_at} 으로 표시한다. "치수가 없는 상품" 으로만 판단하면
 * 촬영 전에 버튼을 다시 눌렀을 때 같은 바코드가 계속 나온다. 리셋이 이 표시를 비우므로
 * 다시 처음부터 나온다.
 */
@Component
public class DemoBarcodeFeeder {

    private final DemoProductRepository demoProducts;
    private final ProductRepository productRepository;

    public DemoBarcodeFeeder(DemoProductRepository demoProducts, ProductRepository productRepository) {
        this.demoProducts = demoProducts;
        this.productRepository = productRepository;
    }

    /** 남은 게 없으면 빈 값 — 호출자는 204 로 답한다. */
    @Transactional
    public Optional<DemoBarcode> next() {
        List<DemoProduct> pending =
                demoProducts.findByPoolAndServedAtIsNullOrderByGtinAsc(DemoProduct.Pool.INBOUND);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        DemoProduct target = pending.getFirst();
        target.markServed();

        String name = productRepository.findByGtin(target.gtin())
                .map(Product::name)
                .orElse(target.gtin());
        return Optional.of(new DemoBarcode(target.gtin(), name, pending.size() - 1));
    }

    /**
     * @param remaining 이 바코드를 뺀 나머지 — 화면이 "3개 중 2개 남음" 을 보여줄 수 있다
     */
    public record DemoBarcode(String barcode, String name, int remaining) {
    }
}
