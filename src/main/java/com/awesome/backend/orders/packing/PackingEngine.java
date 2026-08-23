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
 *
 * 블록마다 축 평행 6방향을 시도한다 (근거는 ROTATION_INDICES 주석).
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

    /**
     * 블록의 세 변을 (가로, 세로, 높이) 축에 배정하는 6가지 순열.
     *
     * 축 정렬 상태에서 직육면체를 돌리는 강체 회전은 24가지지만, 마주보는 면끼리의
     * 180° 뒤집기는 치수 배열이 같아 판정이 동일하다. 24 / 4 = 6 — 여기 6개가
     * 중복을 제거하고 남은 전부다.
     *
     * 6 = 3(바닥에 놓을 면) × 2(그 면을 수직축으로 90° 돌리기).
     * 바닥 면을 정해도 90° 회전이 남는 이유는 박스 바닥이 정사각형이 아니기 때문이다.
     * 블록 10×20×5를 10×20 면이 바닥에 오게 놓아도, 바닥 자국이 10×20이냐 20×10이냐에
     * 따라 유효 내치수 12×22×6 박스의 판정이 수용/불가로 갈린다.
     *
     * 블록 단독의 박스 통과 여부만 볼 때는 블록 세 변과 박스 세 변을 각각 정렬해
     * 짝 비교하면 결과가 같다. 6방향을 도는 이유는 블록이 여럿일 때 회전이 판정뿐
     * 아니라 남는 공간의 모양까지 바꿔, 다음 블록의 배치 가능 여부가 달라지기 때문이다.
     *
     * 중복 제거는 하지 않는다 — 정사각 바닥 블록(a=b)은 6개 중 3개, 정육면체는 6개
     * 전부가 같은 배치다. 판정 결과는 같고 시도 횟수만 늘어난다.
     */
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
