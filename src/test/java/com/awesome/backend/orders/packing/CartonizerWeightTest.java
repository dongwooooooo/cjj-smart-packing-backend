package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 목적함수의 무게 축 — 요금이 편성을 가르는지, 하드 제약이 분할·거부를 만드는지 본다.
 *
 * <p>요금표는 케이스마다 다르게 둔다. V13 seed 요금표는 구간 사이 차이(5000~9000)가 작아
 * 배송단위를 하나 늘리는 순간 항상 비싸지므로, 요금이 편성을 가르는 장면을 만들 수 없다.
 */
class CartonizerWeightTest {

    // 우체국 소포 1·2·3호 (판두께 0.5cm, 박스 자체 무게 0kg)
    private static final List<CatalogBox> CATALOG = List.of(
            box(1, 22, 19, 9), box(2, 27, 18, 15), box(3, 34, 25, 21));

    private static final CarrierLimits LIMITS = new CarrierLimits(160.0, 100.0, 20.0);

    private final Cartonizer cartonizer = new Cartonizer(new PackingEngine(3.0), LIMITS);

    private static CatalogBox box(long id, double w, double l, double h) {
        return CatalogBox.of(id, BoxSpec.ofCm(w, l, h), 0.0, 0.5);
    }

    private static PackItem cube(String gtin, double sideCm, double weightKg) {
        return new PackItem(gtin, Block.ofCm(sideCm, sideCm, sideCm), weightKg, false, false);
    }

    private static RateTable rates(int smallPrice, int mediumPrice, int largePrice) {
        return new RateTable(List.of(
                new RateTable.Tier(1, "극소형", 80, 2, smallPrice),
                new RateTable.Tier(2, "소형", 100, 5, mediumPrice),
                new RateTable.Tier(3, "특대형", 160, 20, largePrice)));
    }

    @Test
    void 무게로_요금_구간이_올라가면_한_박스에_들어가도_나눠_담는다() {
        // 10cm 정육면체 4개(각 1.4kg)는 C호 하나에 들어간다. 그런데 총 5.6kg이라 요금은
        // 특대형 구간(9000) — 나눠 담아 소형·극소형 구간에 걸치면 3000이라 그쪽이 싸다.
        List<PackItem> items = List.of(
                cube("8801", 10, 1.4), cube("8801", 10, 1.4),
                cube("8801", 10, 1.4), cube("8801", 10, 1.4));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG, rates(1000, 2000, 9000));

        assertThat(plans).hasSize(2);
        assertThat(plans.stream().mapToInt(p -> p.items().size()).sum()).isEqualTo(4);
    }

    @Test
    void 같은_입력이라도_요금표가_평평하면_한_박스로_둔다() {
        // 앞 케이스와 낱개·무게가 같다. 구간 요금이 같으면 나눌수록 비싸지므로 통째가 남는다 —
        // 분할을 만든 건 부피나 무게 자체가 아니라 요금이다.
        List<PackItem> items = List.of(
                cube("8801", 10, 1.4), cube("8801", 10, 1.4),
                cube("8801", 10, 1.4), cube("8801", 10, 1.4));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG, rates(5000, 5000, 5000));

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).items()).hasSize(4);
    }

    @Test
    void 총무게가_접수_한도를_넘으면_요금과_무관하게_나눈다() {
        // 8kg짜리 3개 = 24kg. 치수로는 C호 하나에 들어가지만 한도 20kg을 넘어 배송단위가 안 된다.
        // 요금표는 통째가 싸다고 말하지만(9000 < 2×9000) 하드 제약이 먼저다.
        List<PackItem> items = List.of(
                cube("8801", 10, 8.0), cube("8801", 10, 8.0), cube("8801", 10, 8.0));

        List<ShipmentPlan> plans = cartonizer.cartonize(items, CATALOG, rates(9000, 9000, 9000));

        assertThat(plans).hasSize(2);
        assertThat(plans).allSatisfy(plan -> assertThat(
                plan.items().stream().mapToDouble(PackItem::weightKg).sum())
                .isLessThanOrEqualTo(LIMITS.maxWeightKg()));
    }

    @Test
    void 낱개_하나가_무게_한도를_넘으면_주문을_거부한다() {
        // 치수는 A호에도 들어가지만 25kg은 나눠 담을 수 없다
        List<PackItem> items = List.of(cube("8805", 10, 25.0));

        assertThatThrownBy(() -> cartonizer.cartonize(items, CATALOG, rates(1000, 2000, 9000)))
                .isInstanceOf(OverweightItemException.class)
                .hasMessageContaining("8805")
                .extracting(e -> ((OverweightItemException) e).weightKg())
                .isEqualTo(25.0);
    }

    @Test
    void 치수가_안_맞는_낱개는_무게와_상관없이_초과_치수_거부다() {
        List<PackItem> items = List.of(new PackItem("8806", Block.ofCm(100, 10, 10), 0.5, false, false));

        assertThatThrownBy(() -> cartonizer.cartonize(items, CATALOG, rates(1000, 2000, 9000)))
                .isInstanceOf(OversizedItemException.class)
                .hasMessageContaining("8806");
    }

    @Test
    void 무게가_실린_같은_입력이면_같은_편성이_나온다() {
        List<PackItem> items = List.of(
                cube("8801", 10, 1.4), cube("8802", 8, 2.2),
                cube("8803", 12, 0.9), cube("8804", 10, 1.4));
        RateTable rates = rates(1000, 2000, 9000);

        assertThat(cartonizer.cartonize(items, CATALOG, rates))
                .isEqualTo(cartonizer.cartonize(items, CATALOG, rates));
    }

    @Test
    void 요금이_비어_있는_구간은_고르지_않는다() {
        // 극소형 구간의 요금이 미확정이면 그 구간에 걸리는 A호 대신, 요금을 아는 C호를 고른다.
        // 미확정을 0원으로 치면 근거 없이 그쪽을 선호하게 된다.
        RateTable unpriced = new RateTable(List.of(
                new RateTable.Tier(1, "극소형", 80, 2, null),
                new RateTable.Tier(2, "소형", 100, 5, 6000),
                new RateTable.Tier(3, "특대형", 160, 20, 9000)));

        List<ShipmentPlan> plans = cartonizer.cartonize(
                List.of(new PackItem("8807", Block.ofCm(10, 10, 5), 0.5, false, false)),
                CATALOG, unpriced);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).boxId()).isEqualTo(3);
    }
}
