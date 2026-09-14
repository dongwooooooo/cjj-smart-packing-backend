package com.awesome.backend.orders.packing;

import java.util.List;

/**
 * SKU를 편성용 낱개 블록으로 변환 (docs/orders-import-spec.md §4-1).
 */
public class BlockFactory {

    private final double fillerThicknessCm;

    public BlockFactory(double fillerThicknessCm) {
        this.fillerThicknessCm = fillerThicknessCm;
    }

    public List<PackItem> toItems(String gtin, double widthCm, double lengthCm, double heightCm,
                                  double weightKg, boolean fragile, boolean nonStackable, int qty) {
        // 완충재는 상품을 감싸므로 파손주의 블록의 각 변에 양쪽 두께를 더한다
        double pad = fragile ? fillerThicknessCm * 2 : 0;
        Block block = Block.ofCm(widthCm + pad, lengthCm + pad, heightCm + pad);
        PackItem item = new PackItem(gtin, block, weightKg, fragile, nonStackable);
        return java.util.Collections.nCopies(qty, item);
    }
}
