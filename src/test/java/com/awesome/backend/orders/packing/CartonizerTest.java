package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CartonizerTest {

    // 우체국 소포 1~5호 = A~E호 (V2 seed와 동일)
    private static final List<CatalogBox> CATALOG = List.of(
            new CatalogBox(1, BoxSpec.ofCm(22, 19, 9)),
            new CatalogBox(2, BoxSpec.ofCm(27, 18, 15)),
            new CatalogBox(3, BoxSpec.ofCm(34, 25, 21)),
            new CatalogBox(4, BoxSpec.ofCm(41, 31, 28)),
            new CatalogBox(5, BoxSpec.ofCm(48, 38, 34)));

    private final Cartonizer cartonizer = new Cartonizer(new PackingEngine(3.0));

    private static PackItem item(String gtin, double w, double l, double h) {
        return new PackItem(gtin, Block.ofCm(w, l, h), false);
    }

    @Test
    void 전체가_한_박스에_들어가면_배송단위_하나로_최소_박스를_고른다() {
        // 주스 7×7×23 세 개 — B호는 2개까지만 배치 가능, C호에 3개 들어감
        List<PackItem> items = List.of(
                item("8801", 7, 7, 23), item("8801", 7, 7, 23), item("8801", 7, 7, 23));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).boxId()).isEqualTo(3);
        assertThat(plans.get(0).items()).hasSize(3);
    }

    @Test
    void 부피가_큰_박스가_치수로_탈락해도_다음_박스를_검사한다() {
        // 20×16×6: A호는 20>유효19, B호는 16>유효15로 탈락 — C호 선택 (박스 비례 없음)
        List<PackItem> items = List.of(item("8802", 20, 16, 6));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).boxId()).isEqualTo(3);
    }

    @Test
    void 적층불가_상품은_일반_상품과_배송단위를_분리한다() {
        // 전부 한 박스에 들어가는 크기지만 적층불가라 분리돼야 한다
        PackItem irregular = new PackItem("8803", Block.ofCm(10, 10, 5), true);
        PackItem normal = item("8804", 10, 10, 5);

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(irregular, normal), CATALOG);

        assertThat(plans).hasSize(2);
    }

    @Test
    void 같은_적층불가_상품끼리는_한_배송단위를_허용한다() {
        PackItem a = new PackItem("8803", Block.ofCm(10, 10, 5), true);
        PackItem b = new PackItem("8803", Block.ofCm(10, 10, 5), true);

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(a, b), CATALOG);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).items()).hasSize(2);
    }

    @Test
    void 박스_개수가_같으면_총_부피가_작아지는_이동을_채택한다() {
        // FFD 초기해: {대형a, 소형b}→E호, {중형c}→C호 (총부피 79,866cm³)
        // b를 c쪽으로 옮기면 {a}→D호, {c,b}→D호 (총부피 71,176cm³) — 개수 동점, 부피 개선
        PackItem a = item("8806", 35, 25, 22);
        PackItem c = item("8807", 25, 20, 15);
        PackItem b = item("8808", 10, 10, 10);

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(a, c, b), CATALOG);

        assertThat(plans).hasSize(2);
        assertThat(plans).allSatisfy(plan -> assertThat(plan.boxId()).isEqualTo(4));
    }

    @Test
    void 같은_입력이면_같은_편성이_나온다() {
        List<PackItem> items = List.of(
                item("8806", 35, 25, 22), item("8807", 25, 20, 15),
                item("8808", 10, 10, 10), item("8801", 7, 7, 23));

        List<ShipmentPlan> first = cartonizer.cartonize(items, CATALOG);
        List<ShipmentPlan> second = cartonizer.cartonize(items, CATALOG);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 어떤_박스에도_안_들어가는_낱개는_주문을_거부한다() {
        List<PackItem> items = List.of(item("8805", 100, 10, 10));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cartonizer.cartonize(items, CATALOG))
                .isInstanceOf(OversizedItemException.class)
                .hasMessageContaining("8805");
    }
}
