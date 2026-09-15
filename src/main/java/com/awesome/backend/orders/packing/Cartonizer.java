package com.awesome.backend.orders.packing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * 편성 최적화 (docs/orders-import-spec.md §4-3).
 *
 * <p>목적함수: 총 배송비 최소 → 동점이면 박스 개수 최소 → 동점이면 총 부피 최소.
 * 택배 요금은 세 변의 합 구간과 무게 구간 중 높은 쪽으로 정해지므로, 무게를 빼고 박스 개수만
 * 세면 요금이 한 구간 올라가는 편성을 최적이라고 낸다.
 */
public class Cartonizer {

    /**
     * 요금 미확정 구간(price_krw NULL)에 걸린 배송단위의 요금. 비교에서 가장 비싼 것으로 둔다 —
     * 값을 지어내면 근거 없는 선호가 생기고, 0으로 두면 미확정 구간을 골라버린다.
     */
    private static final long UNPRICED = Long.MAX_VALUE;

    private final PackingEngine engine;
    private final CarrierLimits limits;

    public Cartonizer(PackingEngine engine, CarrierLimits limits) {
        this.engine = engine;
        this.limits = limits;
    }

    public List<ShipmentPlan> cartonize(List<PackItem> items, List<CatalogBox> catalog, RateTable rates) {
        if (catalog.isEmpty()) {
            // 설정 오류 — 비즈니스 거부(OVERSIZED_ITEM)와 섞이면 안 된다
            throw new IllegalArgumentException("box catalog is empty");
        }
        List<CatalogBox> ascending = catalog.stream()
                .sorted(Comparator.comparingLong(CatalogBox::innerVolumeMm3))
                .toList();

        // 선검사: 낱개 하나가 어느 박스에도 담기지 않으면 나눠도 해결되지 않으므로 주문 거부.
        // 치수는 담기는데 무게만 걸리는 경우를 가려내 사유를 나눈다.
        for (PackItem item : items) {
            List<PackItem> single = List.of(item);
            if (minBox(single, ascending, rates) != null) {
                continue;
            }
            boolean boxAvailable = ascending.stream()
                    .anyMatch(box -> fitsDimensions(single, box) && limits.allowsDimensions(box));
            if (boxAvailable) {
                throw new OverweightItemException(item.gtin(), item.weightKg());
            }
            throw new OversizedItemException(item.gtin());
        }

        // 적층불가 선분리: 일반 그룹 하나 + 적층불가 GTIN별 그룹 (동거 금지)
        List<ShipmentPlan> plans = new ArrayList<>();
        List<PackItem> regular = items.stream().filter(i -> !i.nonStackable()).toList();
        if (!regular.isEmpty()) {
            plans.addAll(cartonizeGroup(regular, ascending, rates));
        }
        items.stream().filter(PackItem::nonStackable)
                .map(PackItem::gtin).distinct().sorted()
                .forEach(gtin -> plans.addAll(cartonizeGroup(
                        items.stream()
                                .filter(i -> i.nonStackable() && i.gtin().equals(gtin))
                                .toList(),
                        ascending, rates)));
        return plans;
    }

    private List<ShipmentPlan> cartonizeGroup(List<PackItem> group, List<CatalogBox> ascending,
                                              RateTable rates) {
        // 통째 시도: 전체가 박스 1개에 들어가면 그것이 후보 하나다. 박스 개수는 1로 최소지만
        // 요금까지 최소라는 보장이 없어(무게가 구간을 올릴 수 있다) 분할 후보와 비교한다.
        List<List<PackItem>> best = null;
        if (minBox(group, ascending, rates) != null) {
            best = List.of(group);
        }

        List<List<PackItem>> split = improveByMoves(
                firstFitDecreasing(group, ascending, rates), ascending, rates);
        if (best == null || cost(split, ascending, rates).compareTo(cost(best, ascending, rates)) < 0) {
            best = split;
        }

        List<ShipmentPlan> plans = new ArrayList<>();
        for (List<PackItem> unit : best) {
            plans.add(ShipmentPlan.of(minBox(unit, ascending, rates).id(), unit));
        }
        return plans;
    }

    /**
     * 국소 탐색: 낱개 하나를 다른 배송단위(또는 새 배송단위)로 옮겨보고, 목적함수가 좋아지는
     * 이동만 채택. 개선이 없으면 종료.
     *
     * <p>새 배송단위로의 이동까지 보는 이유: 무게 때문에 요금 구간이 올라간 단위는 쪼개는 쪽이
     * 쌀 수 있는데, 기존 단위 사이의 이동만으로는 단위가 늘어나지 않아 그 편성에 닿지 못한다.
     * 채택 조건이 목적함수뿐이라 쪼개는 쪽이 비싸지면 그대로 기각된다.
     */
    private List<List<PackItem>> improveByMoves(List<List<PackItem>> units, List<CatalogBox> ascending,
                                                RateTable rates) {
        boolean improved = true;
        while (improved) {
            improved = false;
            PlanCost currentCost = cost(units, ascending, rates);
            outer:
            for (int from = 0; from < units.size(); from++) {
                for (int to = 0; to <= units.size(); to++) {
                    if (from == to) {
                        continue;
                    }
                    for (int i = 0; i < units.get(from).size(); i++) {
                        List<List<PackItem>> moved = move(units, from, to, i, ascending, rates);
                        if (moved == null) {
                            continue;
                        }
                        if (cost(moved, ascending, rates).compareTo(currentCost) < 0) {
                            units = moved;
                            improved = true;
                            break outer;
                        }
                    }
                }
            }
        }
        return units;
    }

    /**
     * from 단위의 i번째 낱개를 to 단위로 옮긴 새 편성. to가 units.size()면 새 단위로 옮긴다.
     * to가 수용 불가면 null.
     */
    private List<List<PackItem>> move(List<List<PackItem>> units, int from, int to, int i,
                                      List<CatalogBox> ascending, RateTable rates) {
        boolean newUnit = to == units.size();
        if (newUnit && units.get(from).size() == 1) {
            // 단위 하나짜리를 통째로 새 단위에 옮기면 같은 편성이 된다 — 무한 반복 방지
            return null;
        }
        List<PackItem> target = newUnit ? new ArrayList<>() : new ArrayList<>(units.get(to));
        target.add(units.get(from).get(i));
        if (minBox(target, ascending, rates) == null) {
            return null;
        }
        List<PackItem> source = new ArrayList<>(units.get(from));
        source.remove(i);
        if (!source.isEmpty() && minBox(source, ascending, rates) == null) {
            // 낱개를 빼면 오히려 담을 박스가 사라질 수 있다. 배치 판정이 휴리스틱이라 블록이
            // 줄면 놓는 순서와 남는 공간의 모양이 달라져, 실제로는 들어가는 배치를 못 찾는다
            // (§4-2 한계). 박스를 정하지 못하는 편성은 후보가 될 수 없으므로 이 이동을 기각한다.
            return null;
        }
        List<List<PackItem>> result = new ArrayList<>();
        for (int u = 0; u < units.size(); u++) {
            if (u == to) {
                result.add(target);
            } else if (u == from) {
                if (!source.isEmpty()) {
                    result.add(source);
                }
            } else {
                result.add(units.get(u));
            }
        }
        if (newUnit) {
            result.add(target);
        }
        return result;
    }

    /** 편성 하나의 비용 — 사전식 비교용 (총 요금, 박스 개수, 총 부피). */
    private PlanCost cost(List<List<PackItem>> units, List<CatalogBox> ascending, RateTable rates) {
        long fare = 0;
        long volume = 0;
        for (List<PackItem> unit : units) {
            CatalogBox box = minBox(unit, ascending, rates);
            fare = plus(fare, fareOf(box, unit, rates));
            volume += box.innerVolumeMm3();
        }
        return new PlanCost(fare, units.size(), volume);
    }

    /** 요금 미확정(UNPRICED)이 섞이면 합계도 미확정이다 — 넘침 없이 그대로 유지한다. */
    private static long plus(long a, long b) {
        return (a == UNPRICED || b == UNPRICED) ? UNPRICED : a + b;
    }

    private long fareOf(CatalogBox box, List<PackItem> unit, RateTable rates) {
        OptionalLong fare = rates.fareKrw(box.outerSumCm(), totalWeightKg(unit, box));
        return fare.orElse(UNPRICED);
    }

    /** 배송단위 총무게 = Σ상품 무게 + 박스 자체 무게. */
    private static double totalWeightKg(List<PackItem> unit, CatalogBox box) {
        return unit.stream().mapToDouble(PackItem::weightKg).sum() + box.tareWeightKg();
    }

    /**
     * 이 낱개들을 담을 수 있는 박스 중 요금 최소, 동점이면 부피 최소. 없으면 null.
     *
     * <p>담을 수 있다 = 배치 엔진이 수용 판정 + 택배사 접수 한도(세변합·최장변·총무게) 통과.
     */
    private CatalogBox minBox(List<PackItem> unit, List<CatalogBox> ascending, RateTable rates) {
        CatalogBox best = null;
        long bestFare = 0;
        for (CatalogBox box : ascending) {
            if (!fits(unit, box)) {
                continue;
            }
            long fare = fareOf(box, unit, rates);
            // 오름차순 순회라 요금이 같으면 먼저 만난 쪽이 부피가 작다
            if (best == null || fare < bestFare) {
                best = box;
                bestFare = fare;
            }
        }
        return best;
    }

    /** 치수만으로 이 박스에 들어가는가 — 접수 한도는 보지 않는다. */
    private boolean fitsDimensions(List<PackItem> unit, CatalogBox box) {
        return engine.canPack(unit.stream().map(PackItem::block).toList(), box.spec());
    }

    private boolean fits(List<PackItem> unit, CatalogBox box) {
        return fitsDimensions(unit, box) && limits.allows(box, totalWeightKg(unit, box));
    }

    /** 부피 내림차순(동률이면 GTIN 순)으로, 수용 가능한 첫 배송단위에 배치. */
    private List<List<PackItem>> firstFitDecreasing(List<PackItem> items, List<CatalogBox> ascending,
                                                    RateTable rates) {
        List<PackItem> sorted = items.stream()
                .sorted(Comparator.comparingLong(Cartonizer::volume).reversed()
                        .thenComparing(PackItem::gtin))
                .toList();

        List<List<PackItem>> units = new ArrayList<>();
        for (PackItem item : sorted) {
            List<PackItem> target = null;
            for (List<PackItem> unit : units) {
                List<PackItem> candidate = new ArrayList<>(unit);
                candidate.add(item);
                if (minBox(candidate, ascending, rates) != null) {
                    target = unit;
                    break;
                }
            }
            if (target != null) {
                target.add(item);
            } else {
                List<PackItem> unit = new ArrayList<>();
                unit.add(item);
                units.add(unit);
            }
        }
        return units;
    }

    private static long volume(PackItem item) {
        Block b = item.block();
        return (long) b.widthMm() * b.lengthMm() * b.heightMm();
    }

    private record PlanCost(long fareKrw, int units, long volumeMm3) implements Comparable<PlanCost> {

        @Override
        public int compareTo(PlanCost other) {
            return Comparator.comparingLong(PlanCost::fareKrw)
                    .thenComparingInt(PlanCost::units)
                    .thenComparingLong(PlanCost::volumeMm3)
                    .compare(this, other);
        }
    }
}
