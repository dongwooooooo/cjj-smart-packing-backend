package com.awesome.backend.orders.service;

import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.packing.BlockFactory;
import com.awesome.backend.orders.packing.CatalogBox;
import com.awesome.backend.orders.packing.Cartonizer;
import com.awesome.backend.orders.packing.BoxSpec;
import com.awesome.backend.orders.packing.PackItem;
import com.awesome.backend.orders.packing.RateTable;
import com.awesome.backend.orders.packing.ShipmentPlan;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShippingRateTierRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 편성 진입점 (명세 §4). 주문의 낱개를 블록으로 만들어 배송단위로 나눈다.
 *
 * <p>상품과 박스 카탈로그는 배치마다 한 번만 읽는다. 접수 처리 도중 바뀌지 않는 값이고,
 * 주문마다 다시 읽으면 같은 배치 안에서 편성 기준이 달라질 수 있다.
 */
@Component
public class PackingPlanner {

    private final ProductRepository productRepository;
    private final BoxTypeRepository boxTypeRepository;
    private final ShippingRateTierRepository shippingRateTierRepository;
    private final BlockFactory blockFactory;
    private final Cartonizer cartonizer;
    private final PackingProperties properties;

    public PackingPlanner(ProductRepository productRepository, BoxTypeRepository boxTypeRepository,
                          ShippingRateTierRepository shippingRateTierRepository,
                          BlockFactory blockFactory, Cartonizer cartonizer,
                          PackingProperties properties) {
        this.productRepository = productRepository;
        this.boxTypeRepository = boxTypeRepository;
        this.shippingRateTierRepository = shippingRateTierRepository;
        this.blockFactory = blockFactory;
        this.cartonizer = cartonizer;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Plans prepare(OrderImportCommand command) {
        List<String> gtins = command.orders().stream()
                .flatMap(order -> order.items().stream())
                .map(OrderImportCommand.ItemLine::gtin)
                .distinct()
                .toList();
        Map<String, Product> products = productRepository.findByGtinIn(gtins).stream()
                .collect(Collectors.toMap(Product::gtin, Function.identity()));
        List<CatalogBox> catalog = boxTypeRepository.findAll().stream()
                .map(box -> CatalogBox.of(box.id(), BoxSpec.ofCm(
                                box.innerWidthCm().doubleValue(),
                                box.innerLengthCm().doubleValue(),
                                box.innerHeightCm().doubleValue()),
                        box.tareWeightKg().doubleValue(),
                        properties.boardThicknessCm()))
                .toList();
        RateTable rates = new RateTable(shippingRateTierRepository.findAll().stream()
                .map(tier -> new RateTable.Tier(tier.rank(), tier.name(),
                        tier.maxSumCm().doubleValue(), tier.maxWeightKg().doubleValue(),
                        tier.priceKrw()))
                .toList());
        return new Plans(products, catalog, rates);
    }

    /** 한 배치 동안 고정된 상품·카탈로그·요금표 위에서 주문별 편성을 돌린다. */
    public final class Plans {

        private final Map<String, Product> products;
        private final List<CatalogBox> catalog;
        private final RateTable rates;

        private Plans(Map<String, Product> products, List<CatalogBox> catalog, RateTable rates) {
            this.products = products;
            this.catalog = catalog;
            this.rates = rates;
        }

        /** 초과 치수 낱개가 있으면 OversizedItemException. 호출자가 주문 거부로 옮긴다. */
        public List<ShipmentPlan> of(OrderImportCommand.OrderLine order) {
            List<PackItem> items = new ArrayList<>();
            for (OrderImportCommand.ItemLine line : order.items()) {
                Product product = products.get(line.gtin());
                items.addAll(blockFactory.toItems(product.gtin(),
                        cm(product.widthCm()), cm(product.lengthCm()), cm(product.heightCm()),
                        weightKg(product), product.fragile(), product.irregular(), line.qty()));
            }
            return cartonizer.cartonize(items, catalog, rates);
        }

        public long productId(String gtin) {
            return products.get(gtin).id();
        }

        private double cm(BigDecimal value) {
            return value.doubleValue();
        }

        /**
         * 무게 미등록 상품은 0kg으로 본다. 접수는 치수 확정(dim_status=CONFIRMED)만 통과시키지만
         * 무게는 별도 컬럼이라 비어 있을 수 있다 — 이 경우 요금이 실제보다 낮게 잡힌다.
         */
        private double weightKg(Product product) {
            return product.weightKg() == null ? 0.0 : product.weightKg().doubleValue();
        }
    }
}
