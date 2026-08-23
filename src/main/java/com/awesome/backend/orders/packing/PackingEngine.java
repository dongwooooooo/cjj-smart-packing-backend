package com.awesome.backend.orders.packing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 배치 기반 적재 판정 (docs/orders-import-spec.md §4-2).
 *
 * extreme point 방식: 놓인 블록들의 모서리가 만드는 후보점에 블록을 하나씩
 * 놓아보며 실제 배치를 구성한다. 배치가 구성되면 수용 — 무효 추천이 없다.
 * 후보점·회전 순서가 고정이라 같은 입력이면 같은 결과가 나온다.
 */
public class PackingEngine {

    private final int marginMm;

    public PackingEngine(double marginCm) {
        this.marginMm = (int) Math.round(marginCm * 10);
    }

    public boolean canPack(List<Block> blocks, BoxSpec box) {
        int w = box.innerWidthMm() - marginMm;
        int l = box.innerLengthMm() - marginMm;
        int h = box.innerHeightMm() - marginMm;
        if (w <= 0 || l <= 0 || h <= 0) {
            return blocks.isEmpty();
        }

        List<Placement> placed = new ArrayList<>();
        List<Point> candidates = new ArrayList<>(List.of(new Point(0, 0, 0)));

        for (Block block : blocks) {
            Placement placement = tryPlace(block, candidates, placed, w, l, h);
            if (placement == null) {
                return false;
            }
            placed.add(placement);
            candidates.remove(new Point(placement.x(), placement.y(), placement.z()));
            candidates.add(new Point(placement.x() + placement.w(), placement.y(), placement.z()));
            candidates.add(new Point(placement.x(), placement.y() + placement.l(), placement.z()));
            candidates.add(new Point(placement.x(), placement.y(), placement.z() + placement.h()));
        }
        return true;
    }

    private Placement tryPlace(Block block, List<Point> candidates, List<Placement> placed,
                               int boxW, int boxL, int boxH) {
        // 바닥부터, 안쪽부터 — 순서 고정으로 결정성 보장
        List<Point> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(Point::z)
                .thenComparingInt(Point::y)
                .thenComparingInt(Point::x));

        for (Point p : ordered) {
            for (int[] dims : rotations(block)) {
                Placement candidate = new Placement(p.x(), p.y(), p.z(), dims[0], dims[1], dims[2]);
                if (inBounds(candidate, boxW, boxL, boxH) && !overlapsAny(candidate, placed)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static final int[][] ROTATION_INDICES = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0},
    };

    private List<int[]> rotations(Block block) {
        int[] d = {block.widthMm(), block.lengthMm(), block.heightMm()};
        List<int[]> result = new ArrayList<>(6);
        for (int[] idx : ROTATION_INDICES) {
            result.add(new int[] {d[idx[0]], d[idx[1]], d[idx[2]]});
        }
        return result;
    }

    private boolean inBounds(Placement p, int boxW, int boxL, int boxH) {
        return p.x() + p.w() <= boxW && p.y() + p.l() <= boxL && p.z() + p.h() <= boxH;
    }

    private boolean overlapsAny(Placement candidate, List<Placement> placed) {
        for (Placement other : placed) {
            if (candidate.x() < other.x() + other.w() && other.x() < candidate.x() + candidate.w()
                    && candidate.y() < other.y() + other.l() && other.y() < candidate.y() + candidate.l()
                    && candidate.z() < other.z() + other.h() && other.z() < candidate.z() + candidate.h()) {
                return true;
            }
        }
        return false;
    }

    private record Point(int x, int y, int z) {}

    private record Placement(int x, int y, int z, int w, int l, int h) {}
}
