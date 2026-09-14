package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogBoxTest {

    @Test
    void 외치수는_내치수에_판두께를_양쪽으로_더한_값이다() {
        CatalogBox box = CatalogBox.of(1, BoxSpec.ofCm(22, 19, 9), 0.0, 0.5);

        assertThat(box.outerWidthMm()).isEqualTo(230);
        assertThat(box.outerLengthMm()).isEqualTo(200);
        assertThat(box.outerHeightMm()).isEqualTo(100);
        assertThat(box.outerSumCm()).isEqualTo(53.0);
        assertThat(box.outerLongestCm()).isEqualTo(23.0);
    }

    @Test
    void 부피_비교는_내치수_기준이다() {
        CatalogBox box = CatalogBox.of(1, BoxSpec.ofCm(10, 10, 10), 0.0, 0.5);

        assertThat(box.innerVolumeMm3()).isEqualTo(1_000_000L);
    }

    @Test
    void 하드_제약은_외치수와_총무게로_판정한다() {
        CarrierLimits limits = new CarrierLimits(160.0, 100.0, 20.0);
        // 내치수 세변합 159cm — 외치수로는 162cm라 한도를 넘는다
        CatalogBox tooBig = CatalogBox.of(1, BoxSpec.ofCm(60, 50, 49), 0.0, 0.5);
        CatalogBox ok = CatalogBox.of(2, BoxSpec.ofCm(48, 38, 34), 0.0, 0.5);

        assertThat(limits.allows(tooBig, 1.0)).isFalse();
        assertThat(limits.allows(ok, 1.0)).isTrue();
        assertThat(limits.allows(ok, 20.0)).isTrue();
        assertThat(limits.allows(ok, 20.1)).isFalse();
    }

    @Test
    void 최장변_한도는_세변합과_따로_본다() {
        CarrierLimits limits = new CarrierLimits(160.0, 100.0, 20.0);
        // 세변합 112cm로 여유가 있어도 최장변 105cm가 한도를 넘는다
        CatalogBox longBox = CatalogBox.of(1, BoxSpec.ofCm(104, 4, 2), 0.0, 0.5);

        assertThat(longBox.outerSumCm()).isLessThan(limits.maxSumCm());
        assertThat(limits.allows(longBox, 1.0)).isFalse();
    }
}
