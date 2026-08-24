package com.awesome.backend.orders.service;

import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.packing.BlockFactory;
import com.awesome.backend.orders.packing.CatalogBox;
import com.awesome.backend.orders.packing.Cartonizer;
import com.awesome.backend.orders.packing.BoxSpec;
import com.awesome.backend.orders.packing.PackItem;
import com.awesome.backend.orders.packing.ShipmentPlan;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
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
    private final BlockFactory blockFactory;
    private final Cartonizer cartonizer;

    public PackingPlanner(ProductRepository productRepository, BoxTypeRepository boxTypeRepository,
                          BlockFactory blockFactory, Cartonizer cartonizer) {
        this.productRepository = productRepository;
        this.boxTypeRepository = boxTypeRepository;
        this.blockFactory = blockFactory;
        this.cartonizer = cartonizer;
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
                .map(box -> new CatalogBox(box.id(), BoxSpec.ofCm(
                        box.innerWidthCm().doubleValue(),
                        box.innerLengthCm().doubleValue(),
                        box.innerHeightCm().doubleValue())))
                .toList();
        return new Plans(products, catalog);
    }

    /** 한 배치 동안 고정된 상품·카탈로그 위에서 주문별 편성을 돌린다. */
    public final class Plans {

        private final Map<String, Product> products;
        private final List<CatalogBox> catalog;

        private Plans(Map<String, Product> products, List<CatalogBox> catalog) {
            this.products = products;
            this.catalog = catalog;
        }

        /** 초과 치수 낱개가 있으면 OversizedItemException. 호출자가 주문 거부로 옮긴다. */
        public List<ShipmentPlan> of(OrderImportCommand.OrderLine order) {
            List<PackItem> items = new ArrayList<>();
            for (OrderImportCommand.ItemLine line : order.items()) {
                Product product = products.get(line.gtin());
                items.addAll(blockFactory.toItems(product.gtin(),
                        cm(product.widthCm()), cm(product.lengthCm()), cm(product.heightCm()),
                        product.fragile(), product.irregular(), line.qty()));
            }
            return cartonizer.cartonize(items, catalog);
        }

        public long productId(String gtin) {
            return products.get(gtin).id();
        }

        private double cm(BigDecimal value) {
            return value.doubleValue();
        }
    }
}
