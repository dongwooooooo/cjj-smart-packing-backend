package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlockFactoryTest {

    // 완충재 두께 1cm (docs/orders-import-spec.md 4-6)
    private final BlockFactory factory = new BlockFactory(1.0);

    @Test
    void 파손주의_상품은_각_변에_완충재_두께_양쪽만큼_커진다() {
        List<PackItem> items = factory.toItems("8801", 23, 14, 11, true, false, 1);

        assertThat(items).hasSize(1);
        Block block = items.get(0).block();
        assertThat(block.widthMm()).isEqualTo(250);
        assertThat(block.lengthMm()).isEqualTo(160);
        assertThat(block.heightMm()).isEqualTo(130);
        // 파손주의 여부는 완충재 권유 표시의 원천 — 버리면 안 된다
        assertThat(items.get(0).fragile()).isTrue();
    }

    @Test
    void 수량_0이면_낱개를_만들지_않는다() {
        assertThat(factory.toItems("8803", 10, 10, 10, false, false, 0)).isEmpty();
    }

    @Test
    void 일반_상품은_치수_그대로_수량만큼_낱개를_만든다() {
        List<PackItem> items = factory.toItems("8802", 20, 15, 5, false, true, 3);

        assertThat(items).hasSize(3);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.block()).isEqualTo(Block.ofCm(20, 15, 5));
            assertThat(item.nonStackable()).isTrue();
            assertThat(item.gtin()).isEqualTo("8802");
        });
    }
}
