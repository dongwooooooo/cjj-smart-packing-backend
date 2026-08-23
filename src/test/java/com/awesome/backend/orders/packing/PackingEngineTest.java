package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PackingEngineTest {

    // 마진 3cm (docs/orders-import-spec.md 4-6)
    private final PackingEngine engine = new PackingEngine(3.0);

    @Test
    void 회전_없이_들어가는_블록_하나는_수용한다() {
        // B호 내치수 27×18×15 → 유효 24×15×12
        BoxSpec box = BoxSpec.ofCm(27, 18, 15);
        Block block = Block.ofCm(20, 10, 10);

        assertThat(engine.canPack(List.of(block), box)).isTrue();
    }

    @Test
    void 눕혀야만_들어가는_블록을_수용한다() {
        // 우유 7×7×24 — 세우면 유효 높이 12 초과, 눕히면 (24,7,7)로 들어감
        BoxSpec box = BoxSpec.ofCm(27, 18, 15);
        Block block = Block.ofCm(7, 7, 24);

        assertThat(engine.canPack(List.of(block), box)).isTrue();
    }

    @Test
    void 어떤_회전으로도_초과하는_블록은_거부한다() {
        // 최장변 25가 B호 유효 최장변 24 초과
        BoxSpec box = BoxSpec.ofCm(27, 18, 15);
        Block block = Block.ofCm(25, 5, 5);

        assertThat(engine.canPack(List.of(block), box)).isFalse();
    }

    @Test
    void 내치수엔_들어가지만_유효_내치수를_넘으면_거부한다() {
        // 26×17×14는 내치수(27×18×15) 이하지만 유효(24×15×12) 초과 — 마진 경계
        BoxSpec box = BoxSpec.ofCm(27, 18, 15);
        Block block = Block.ofCm(26, 17, 14);

        assertThat(engine.canPack(List.of(block), box)).isFalse();
    }

    @Test
    void 부피_합은_남아도_배치가_불가능한_블록들은_거부한다() {
        // 유효 30×30×30 (부피 27,000), 블록 25×25×20 두 개 (부피 합 25,000)
        // 쌓으면 높이 40, 나란히 놓으면 폭 50 — 어떤 배치로도 불가
        BoxSpec box = BoxSpec.ofCm(33, 33, 33);
        Block block = Block.ofCm(25, 25, 20);

        assertThat(engine.canPack(List.of(block, block), box)).isFalse();
    }

    @Test
    void 바닥을_덮는_블록_위에_나머지를_회전시켜_쌓는_조합을_수용한다() {
        // 유효 20×20×20. 20×20×10이 바닥을 덮고, 나머지 둘은 윗층에 눕혀 들어간다
        BoxSpec box = BoxSpec.ofCm(23, 23, 23);
        Block flat = Block.ofCm(20, 20, 10);
        Block tall = Block.ofCm(10, 10, 20);
        Block small = Block.ofCm(20, 10, 10);

        assertThat(engine.canPack(List.of(flat, tall, small), box)).isTrue();
    }

    @Test
    void 배치_가능한_여러_블록은_수용한다() {
        // 유효 30×30×30에 15×15×10 여덟 개 — 2×2×2 배열로 들어감
        BoxSpec box = BoxSpec.ofCm(33, 33, 33);
        Block block = Block.ofCm(15, 15, 10);

        assertThat(engine.canPack(List.of(
                block, block, block, block, block, block, block, block), box)).isTrue();
    }
}
