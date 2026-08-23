package com.awesome.backend.orders.packing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 편성 최적화 (docs/orders-import-spec.md §4-3).
 * 목적함수: 박스 개수 최소, 동점이면 총 부피 최소.
 */
public class Cartonizer {

    private final PackingEngine engine;

    public Cartonizer(PackingEngine engine) {
        this.engine = engine;
    }

    public List<ShipmentPlan> cartonize(List<PackItem> items, List<CatalogBox> catalog) {
        if (catalog.isEmpty()) {
            // 설정 오류 — 비즈니스 거부(OVERSIZED_ITEM)와 섞이면 안 된다
            throw new IllegalArgumentException("box catalog is empty");
        }
        List<CatalogBox> ascending = catalog.stream()
                .sorted(Comparator.comparingLong(CatalogBox::innerVolumeMm3))
                .toList();

        // 초과 치수 선검사: 어떤 박스에도 안 들어가는 낱개가 있으면 주문 거부
        for (PackItem item : items) {
            if (minBox(List.of(item), ascending) == null) {
                throw new OversizedItemException(item.gtin());
            }
        }

        // 적층불가 선분리: 일반 그룹 하나 + 적층불가 GTIN별 그룹 (동거 금지)
        List<ShipmentPlan> plans = new ArrayList<>();
        List<PackItem> regular = items.stream().filter(i -> !i.nonStackable()).toList();
        if (!regular.isEmpty()) {
            plans.addAll(cartonizeGroup(regular, ascending));
        }
        items.stream().filter(PackItem::nonStackable)
                .map(PackItem::gtin).distinct().sorted()
                .forEach(gtin -> plans.addAll(cartonizeGroup(
                        items.stream()
                                .filter(i -> i.nonStackable() && i.gtin().equals(gtin))
                                .toList(),
                        ascending)));
        return plans;
    }

    private List<ShipmentPlan> cartonizeGroup(List<PackItem> group, List<CatalogBox> ascending) {
        // 통째 시도: 전체가 박스 1개에 들어가면 그 최소 박스가 곧 최적
        CatalogBox whole = minBox(group, ascending);
        if (whole != null) {
            return List.of(ShipmentPlan.of(whole.id(), List.copyOf(group)));
        }

        List<List<PackItem>> units = firstFitDecreasing(group, ascending);
        units = improveByMoves(units, ascending);
        List<ShipmentPlan> plans = new ArrayList<>();
        for (List<PackItem> unit : units) {
            plans.add(ShipmentPlan.of(minBox(unit, ascending).id(), unit));
        }
        return plans;
    }

    /**
     * 국소 탐색: 낱개 하나를 다른 배송단위로 옮겨보고, 목적함수
     * (박스 개수, 동점이면 총 부피)가 좋아지는 이동만 채택. 개선이 없으면 종료.
     */
    private List<List<PackItem>> improveByMoves(List<List<PackItem>> units, List<CatalogBox> ascending) {
        boolean improved = true;
        while (improved) {
            improved = false;
            long currentCost = totalVolume(units, ascending);
            outer:
            for (int from = 0; from < units.size(); from++) {
                for (int to = 0; to < units.size(); to++) {
                    if (from == to) {
                        continue;
                    }
                    for (int i = 0; i < units.get(from).size(); i++) {
                        List<List<PackItem>> moved = move(units, from, to, i, ascending);
                        if (moved == null) {
                            continue;
                        }
                        boolean fewerUnits = moved.size() < units.size();
                        if (fewerUnits || totalVolume(moved, ascending) < currentCost) {
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

    /** from 단위의 i번째 낱개를 to 단위로 옮긴 새 편성. to가 수용 불가면 null. */
    private List<List<PackItem>> move(List<List<PackItem>> units, int from, int to, int i,
                                      List<CatalogBox> ascending) {
        List<PackItem> target = new ArrayList<>(units.get(to));
        target.add(units.get(from).get(i));
        if (minBox(target, ascending) == null) {
            return null;
        }
        List<List<PackItem>> result = new ArrayList<>();
        for (int u = 0; u < units.size(); u++) {
            if (u == to) {
                result.add(target);
            } else if (u == from) {
                List<PackItem> source = new ArrayList<>(units.get(from));
                source.remove(i);
                if (!source.isEmpty()) {
                    result.add(source);
                }
            } else {
                result.add(units.get(u));
            }
        }
        return result;
    }

    private long totalVolume(List<List<PackItem>> units, List<CatalogBox> ascending) {
        long sum = 0;
        for (List<PackItem> unit : units) {
            sum += minBox(unit, ascending).innerVolumeMm3();
        }
        return sum;
    }

    /** 오름차순 카탈로그에서 이 낱개들을 수용하는 첫(=최소) 박스. 없으면 null. */
    private CatalogBox minBox(List<PackItem> unit, List<CatalogBox> ascending) {
        List<Block> blocks = unit.stream().map(PackItem::block).toList();
        for (CatalogBox box : ascending) {
            if (engine.canPack(blocks, box.spec())) {
                return box;
            }
        }
        return null;
    }

    /** 부피 내림차순(동률이면 GTIN 순)으로, 수용 가능한 첫 배송단위에 배치. */
    private List<List<PackItem>> firstFitDecreasing(List<PackItem> items, List<CatalogBox> ascending) {
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
                if (minBox(candidate, ascending) != null) {
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
}
