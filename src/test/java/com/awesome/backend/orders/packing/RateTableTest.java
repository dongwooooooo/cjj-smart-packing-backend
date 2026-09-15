package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RateTableTest {

    // V13 seed와 같은 구간표 (CJ대한통운 표준운임 2024, 동일권역)
    private static final RateTable TABLE = new RateTable(List.of(
            new RateTable.Tier(1, "극소형", 80, 2, 5000),
            new RateTable.Tier(2, "소형", 100, 5, 6000),
            new RateTable.Tier(3, "중형", 120, 10, 7000),
            new RateTable.Tier(4, "대형", 140, 15, 8000),
            new RateTable.Tier(5, "특대형", 160, 20, 9000)));

    @Test
    void 세변합과_무게_중_높은_구간의_요금을_매긴다() {
        // 세변합 53cm는 극소형이지만 무게 7kg이 중형 — 높은 쪽인 중형 요금
        assertThat(TABLE.fareKrw(53, 7)).hasValue(7000);
    }

    @Test
    void 무게가_가벼워도_세변합이_크면_그_구간을_매긴다() {
        assertThat(TABLE.fareKrw(143, 0.5)).hasValue(9000);
    }

    @Test
    void 구간_경계값은_그_구간에_포함된다() {
        assertThat(TABLE.fareKrw(80, 2)).hasValue(5000);
        assertThat(TABLE.fareKrw(80.1, 2)).hasValue(6000);
    }

    @Test
    void 구간표를_넘어서면_요금이_없다() {
        assertThat(TABLE.tierFor(161, 1)).isEmpty();
        assertThat(TABLE.fareKrw(50, 25)).isEmpty();
    }

    @Test
    void 구간은_rank_순으로_정렬돼_입력_순서를_타지_않는다() {
        RateTable shuffled = new RateTable(List.of(
                new RateTable.Tier(3, "중형", 120, 10, 7000),
                new RateTable.Tier(1, "극소형", 80, 2, 5000),
                new RateTable.Tier(2, "소형", 100, 5, 6000)));

        assertThat(shuffled.tiers()).extracting(RateTable.Tier::rank).containsExactly(1, 2, 3);
        assertThat(shuffled.fareKrw(53, 1)).hasValue(5000);
    }

    @Test
    void 요금이_비어_있는_구간은_미확정으로_둔다() {
        // 구간 선택은 rank·한도로만 하므로 요금이 NULL이어도 순서는 흔들리지 않는다.
        // 다만 그 구간에 걸린 배송단위는 요금을 매길 수 없다.
        RateTable withUnpriced = new RateTable(List.of(
                new RateTable.Tier(2, "소형", 100, 5, 6000),
                new RateTable.Tier(1, "극소형", 80, 2, null)));

        assertThat(withUnpriced.tiers()).extracting(RateTable.Tier::rank).containsExactly(1, 2);
        assertThat(withUnpriced.tierFor(53, 1)).get()
                .extracting(RateTable.Tier::name).isEqualTo("극소형");
        assertThat(withUnpriced.fareKrw(53, 1)).isEmpty();
        assertThat(withUnpriced.fareKrw(90, 1)).hasValue(6000);
    }
}
