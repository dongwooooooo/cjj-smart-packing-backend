package com.awesome.backend.demo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.orders.packing.BlockFactory;
import com.awesome.backend.orders.packing.BoxSpec;
import com.awesome.backend.orders.packing.CatalogBox;
import com.awesome.backend.orders.packing.Cartonizer;
import com.awesome.backend.orders.packing.PackItem;
import com.awesome.backend.orders.packing.PackingEngine;
import com.awesome.backend.orders.packing.ShipmentPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 레포에 들어 있는 샘플 데이터가 시연에서 쓸 수 있는 상태인지 본다.
 *
 * <p>형식이 맞는지는 로더가 보고, 여기서는 내용이 맞는지 본다 — 주문이 출고 풀에 없는
 * 상품을 부르지 않는지, 편성 케이스(합포장·분할·완충재)가 실제로 그렇게 나오는지.
 * 데이터셋에서 뽑은 실물로 바뀌어도 이 조건은 그대로 지켜야 한다.
 */
class DemoSampleDataTest {

    private static final Path DATA_DIR = Path.of("demo/data");

    // seed의 박스 A~E호 내치수 (V2)
    private static final List<CatalogBox> CATALOG = List.of(
            new CatalogBox(1, BoxSpec.ofCm(22.0, 19.0, 9.0)),
            new CatalogBox(2, BoxSpec.ofCm(27.0, 18.0, 15.0)),
            new CatalogBox(3, BoxSpec.ofCm(34.0, 25.0, 21.0)),
            new CatalogBox(4, BoxSpec.ofCm(41.0, 31.0, 28.0)),
            new CatalogBox(5, BoxSpec.ofCm(48.0, 38.0, 34.0)));

    private final DemoDataLoader loader = new DemoDataLoader();
    private final BlockFactory blockFactory = new BlockFactory(1.0);
    private final Cartonizer cartonizer = new Cartonizer(new PackingEngine(3.0));

    @Test
    void 두_풀에_상품이_고르게_들어_있다() {
        List<DemoProductSpec> products = loader.loadProducts(DATA_DIR);

        assertThat(products).hasSize(6);
        assertThat(products).filteredOn(p -> p.pool() == DemoProduct.Pool.INBOUND).hasSize(3);
        assertThat(products).filteredOn(p -> p.pool() == DemoProduct.Pool.OUTBOUND).hasSize(3);
    }

    @Test
    void 입고_풀은_재고가_비어_있고_이미지가_실제로_있다() {
        for (DemoProductSpec product : loader.loadProducts(DATA_DIR)) {
            if (product.pool() != DemoProduct.Pool.INBOUND) {
                continue;
            }
            assertThat(product.stockQty()).isZero();
            assertThat(product.images()).hasSize(3);
            for (String image : product.images()) {
                assertThat(Files.exists(DATA_DIR.resolve(image)))
                        .as("이미지 파일 %s", image).isTrue();
            }
        }
    }

    @Test
    void 출고_풀에는_파손주의_상품이_하나_이상_있다() {
        // 완충재 권유 장면을 만들려면 필요하다
        assertThat(loader.loadProducts(DATA_DIR))
                .filteredOn(p -> p.pool() == DemoProduct.Pool.OUTBOUND)
                .anyMatch(DemoProductSpec::fragile);
    }

    @Test
    void 주문이_부르는_상품은_전부_출고_풀에_있다() {
        Map<String, DemoProductSpec> byGtin = productsByGtin();

        for (String gtin : orderedGtins()) {
            assertThat(byGtin).as("주문이 부르는 상품 %s", gtin).containsKey(gtin);
            assertThat(byGtin.get(gtin).pool())
                    .as("상품 %s의 풀", gtin).isEqualTo(DemoProduct.Pool.OUTBOUND);
        }
    }

    @Test
    void 배치는_투입_순서대로_세_개다() {
        List<String> batches = loader.loadOrderBatches(DATA_DIR);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).contains("DEMO-1");
        assertThat(batches.get(1)).contains("DEMO-2");
        assertThat(batches.get(2)).contains("DEMO-3");
    }

    @Test
    void 합포장_주문은_한_배송단위로_나온다() {
        assertThat(planFor("R-DEMO-0001")).hasSize(1);
    }

    @Test
    void 분할_주문은_배송단위가_둘_이상으로_나뉜다() {
        assertThat(planFor("R-DEMO-0002")).hasSizeGreaterThan(1);
    }

    @Test
    void 파손주의_주문은_완충재_권유가_켜진다() {
        assertThat(planFor("R-DEMO-0003")).anyMatch(ShipmentPlan::fillerRecommended);
    }

    private List<ShipmentPlan> planFor(String receiptNo) {
        Map<String, DemoProductSpec> byGtin = productsByGtin();
        List<PackItem> items = new ArrayList<>();
        for (JsonNode item : orderNode(receiptNo).path("items")) {
            DemoProductSpec product = byGtin.get(item.path("gtin").asText());
            items.addAll(blockFactory.toItems(product.gtin(),
                    product.widthCm().doubleValue(), product.lengthCm().doubleValue(),
                    product.heightCm().doubleValue(),
                    product.fragile(), product.irregular(), item.path("qty").asInt()));
        }
        return cartonizer.cartonize(items, CATALOG);
    }

    private Map<String, DemoProductSpec> productsByGtin() {
        return loader.loadProducts(DATA_DIR).stream()
                .collect(Collectors.toMap(DemoProductSpec::gtin, Function.identity()));
    }

    private List<String> orderedGtins() {
        List<String> gtins = new ArrayList<>();
        for (JsonNode batch : batchNodes()) {
            for (JsonNode order : batch.path("orders")) {
                for (JsonNode item : order.path("items")) {
                    gtins.add(item.path("gtin").asText());
                }
            }
        }
        return gtins;
    }

    private JsonNode orderNode(String receiptNo) {
        for (JsonNode batch : batchNodes()) {
            for (JsonNode order : batch.path("orders")) {
                if (receiptNo.equals(order.path("receiptNo").asText())) {
                    return order;
                }
            }
        }
        throw new AssertionError("주문 " + receiptNo + "가 orders.json에 없다");
    }

    private List<JsonNode> batchNodes() {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> nodes = new ArrayList<>();
        for (String batch : loader.loadOrderBatches(DATA_DIR)) {
            try {
                nodes.add(mapper.readTree(batch));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return nodes;
    }
}
