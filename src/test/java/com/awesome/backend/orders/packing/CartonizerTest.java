package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CartonizerTest {

    // 우체국 소포 1~5호 = A~E호 (V2 seed와 동일)
    private static final List<CatalogBox> CATALOG = List.of(
            box(1, 22, 19, 9), box(2, 27, 18, 15), box(3, 34, 25, 21),
            box(4, 41, 31, 28), box(5, 48, 38, 34));

    private static CatalogBox box(long id, double w, double l, double h) {
        // 판두께 0.5cm, 박스 자체 무게 0kg (V13 seed와 같은 잠정값)
        return CatalogBox.of(id, BoxSpec.ofCm(w, l, h), 0.0, 0.5);
    }

    // V13 seed와 같은 요금 구간표 (CJ대한통운 표준운임 2024, 동일권역)
    private static final RateTable RATES = new RateTable(List.of(
            new RateTable.Tier(1, "극소형", 80, 2, 5000),
            new RateTable.Tier(2, "소형", 100, 5, 6000),
            new RateTable.Tier(3, "중형", 120, 10, 7000),
            new RateTable.Tier(4, "대형", 140, 15, 8000),
            new RateTable.Tier(5, "특대형", 160, 20, 9000)));

    private static final CarrierLimits LIMITS = new CarrierLimits(160.0, 100.0, 20.0);

    private final Cartonizer cartonizer = new Cartonizer(new PackingEngine(3.0), LIMITS);

    private static PackItem item(String gtin, double w, double l, double h) {
        return item(gtin, w, l, h, 0.0);
    }

    private static PackItem item(String gtin, double w, double l, double h, double weightKg) {
        return new PackItem(gtin, Block.ofCm(w, l, h), weightKg, false, false);
    }

    @Test
    void 전체가_한_박스에_들어가면_배송단위_하나로_최소_박스를_고른다() {
        // 주스 7×7×23 세 개 — B호는 2개까지만 배치 가능, C호에 3개 들어감
        List<PackItem> items = List.of(
                item("8801", 7, 7, 23), item("8801", 7, 7, 23), item("8801", 7, 7, 23));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG, RATES);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).boxId()).isEqualTo(3);
        assertThat(plans.get(0).items()).hasSize(3);
    }

    @Test
    void 부피가_큰_박스가_치수로_탈락해도_다음_박스를_검사한다() {
        // 20×16×6: A호는 20>유효19, B호는 16>유효15로 탈락 — C호 선택 (박스 비례 없음)
        List<PackItem> items = List.of(item("8802", 20, 16, 6));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG, RATES);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).boxId()).isEqualTo(3);
    }

    @Test
    void 적층불가_상품은_일반_상품과_배송단위를_분리한다() {
        // 전부 한 박스에 들어가는 크기지만 적층불가라 분리돼야 한다
        PackItem irregular = new PackItem("8803", Block.ofCm(10, 10, 5), 0.0, false, true);
        PackItem normal = item("8804", 10, 10, 5);

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(irregular, normal), CATALOG, RATES);

        assertThat(plans).hasSize(2);
    }

    @Test
    void 같은_적층불가_상품끼리는_한_배송단위를_허용한다() {
        PackItem a = new PackItem("8803", Block.ofCm(10, 10, 5), 0.0, false, true);
        PackItem b = new PackItem("8803", Block.ofCm(10, 10, 5), 0.0, false, true);

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(a, b), CATALOG, RATES);

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

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(a, c, b), CATALOG, RATES);

        assertThat(plans).hasSize(2);
        assertThat(plans).allSatisfy(plan -> assertThat(plan.boxId()).isEqualTo(4));
        // 이동 과정에서 낱개가 소실·중복되지 않아야 한다
        assertThat(plans.stream().mapToInt(p -> p.items().size()).sum()).isEqualTo(3);
    }

    @Test
    void 파손주의_상품이_있는_배송단위만_완충재를_권유한다() {
        PackItem fragile = new PackItem("8809", Block.ofCm(10, 10, 5), 0.0, true, false);
        PackItem normal = item("8810", 40.5, 30.5, 28); // 어느 축에도 5cm 틈이 안 남아 분리됨

        List<ShipmentPlan> plans = cartonizer.cartonize(List.of(fragile, normal), CATALOG, RATES);

        assertThat(plans).hasSize(2);
        assertThat(plans).anySatisfy(p -> {
            assertThat(p.items().get(0).gtin()).isEqualTo("8809");
            assertThat(p.fillerRecommended()).isTrue();
        });
        assertThat(plans).anySatisfy(p -> {
            assertThat(p.items().get(0).gtin()).isEqualTo("8810");
            assertThat(p.fillerRecommended()).isFalse();
        });
    }

    @Test
    void 박스_카탈로그가_비어_있으면_설정_오류다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cartonizer.cartonize(List.of(item("8801", 7, 7, 23)), List.of(), RATES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_입력이면_같은_편성이_나온다() {
        List<PackItem> items = List.of(
                item("8806", 35, 25, 22), item("8807", 25, 20, 15),
                item("8808", 10, 10, 10), item("8801", 7, 7, 23));

        List<ShipmentPlan> first = cartonizer.cartonize(items, CATALOG, RATES);
        List<ShipmentPlan> second = cartonizer.cartonize(items, CATALOG, RATES);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 어떤_박스에도_안_들어가는_낱개는_주문을_거부한다() {
        List<PackItem> items = List.of(item("8805", 100, 10, 10));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cartonizer.cartonize(items, CATALOG, RATES))
                .isInstanceOf(OversizedItemException.class)
                .hasMessageContaining("8805");
    }
}
