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
    void 두_풀에_시연할_만큼의_상품이_들어_있다() {
        // 개수를 못박지 않는다 — 시연 상품은 사진 확보 여부에 따라 바뀐다. 대신 각 풀이
        // 장면을 만들 수 있는 최소치를 넘는지 본다 (입고 3회전, 출고 합포장·분할·완충재).
        List<DemoProductSpec> products = loader.loadProducts(DATA_DIR);

        assertThat(products).filteredOn(p -> p.pool() == DemoProduct.Pool.INBOUND)
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(products).filteredOn(p -> p.pool() == DemoProduct.Pool.OUTBOUND)
                .hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void 입고_풀은_재고가_비어_있고_카메라_3대분_이미지를_가리킨다() {
        // 사진 실물은 S3 에 있고 저장소에 두지 않는다 (D-25). 여기서는 키가 카메라 3대분으로
        // 선언돼 있는지만 본다 — 실물이 올라갔는지는 배포 절차(demo/README.md)가 확인한다.
        for (DemoProductSpec product : loader.loadProducts(DATA_DIR)) {
            if (product.pool() != DemoProduct.Pool.INBOUND) {
                continue;
            }
            assertThat(product.stockQty()).isZero();
            assertThat(product.images()).hasSize(3);
            for (short cameraNo = 1; cameraNo <= 3; cameraNo++) {
                assertThat(product.images().get(cameraNo - 1))
                        .isEqualTo("images/%s/cam%d.jpg".formatted(product.gtin(), cameraNo));
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
    void 배치는_투입_순서대로_번호가_이어진다() {
        // 개수는 파일이 정한다. 여기서는 DEMO-1 부터 빠짐없이 이어지는지만 본다 —
        // 투입이 맨 앞부터 하나씩이라 번호가 비면 순서가 어긋난다.
        List<String> batches = loader.loadOrderBatches(DATA_DIR);

        assertThat(batches).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 0; i < batches.size(); i++) {
            assertThat(batches.get(i)).contains("DEMO-" + (i + 1));
        }
    }

    @Test
    void 앞선_세_배치가_합포장_분할_완충재를_보여준다() {
        // 시연을 열면 세 장면이 차례로 나온다. 뒤 배치들은 대시보드를 채우는 몫이라
        // 구성이 정해져 있지 않다.
        assertThat(planFor("R-DEMO-0001")).hasSize(1);
        assertThat(planFor("R-DEMO-0002")).hasSizeGreaterThan(1);
        assertThat(planFor("R-DEMO-0003")).anyMatch(ShipmentPlan::fillerRecommended);
    }

    @Test
    void 거부되는_주문은_들어_있지_않다() {
        // 시연 시나리오에 실패 케이스가 없다. 배송지역은 기준정보에 있는 것만 쓴다
        Map<String, DemoProductSpec> byGtin = productsByGtin();
        for (JsonNode batch : batchNodes()) {
            for (JsonNode order : batch.path("orders")) {
                assertThat(order.path("regionCode").asText())
                        .isIn("SEOUL", "GYEONGGI", "BUSAN");
                for (JsonNode item : order.path("items")) {
                    DemoProductSpec product = byGtin.get(item.path("gtin").asText());
                    assertThat(item.path("qty").asInt())
                            .as("주문 수량이 재고 안에 있어야 한다 — %s", product.gtin())
                            .isLessThanOrEqualTo(product.stockQty());
                }
            }
        }
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
