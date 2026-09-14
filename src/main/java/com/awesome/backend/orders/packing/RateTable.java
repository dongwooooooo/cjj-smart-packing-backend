package com.awesome.backend.orders.packing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 택배 요금 구간표 (기준정보 shipping_rate_tier).
 *
 * <p>배송단위 요금은 세 변의 합 구간과 무게 구간 중 <b>높은 쪽</b>으로 정해진다. 구간표의
 * max_sum_cm과 max_weight_kg가 둘 다 rank 순으로 커지므로, "두 구간 중 높은 쪽"은 "세변합과
 * 무게를 동시에 수용하는 첫 구간"과 같다 — 그렇게 구현했다.
 */
public record RateTable(List<Tier> tiers) {

    public RateTable {
        tiers = tiers.stream().sorted(Comparator.comparingInt(Tier::rank)).toList();
    }

    /** 세변합·무게를 모두 수용하는 최저 rank 구간. 구간표를 넘어서면 empty. */
    public Optional<Tier> tierFor(double sumCm, double weightKg) {
        return tiers.stream()
                .filter(tier -> sumCm <= tier.maxSumCm() && weightKg <= tier.maxWeightKg())
                .findFirst();
    }

    /** 해당 구간의 요금. 구간을 못 찾거나 요금이 비어 있으면 empty (= 요금 미확정). */
    public OptionalLong fareKrw(double sumCm, double weightKg) {
        return tierFor(sumCm, weightKg)
                .filter(tier -> tier.priceKrw() != null)
                .map(tier -> OptionalLong.of(tier.priceKrw()))
                .orElseGet(OptionalLong::empty);
    }

    /**
     * @param rank     구간 순서. 요금이 동률인 구간끼리도 순서가 어긋나지 않게 별도로 둔다.
     * @param priceKrw 요금. null이면 미확정 — 요금을 지어내지 않고 미확정으로 다룬다.
     */
    public record Tier(int rank, String name, double maxSumCm, double maxWeightKg, Integer priceKrw) {
    }
}
