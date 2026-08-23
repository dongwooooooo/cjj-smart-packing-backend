package com.awesome.backend.orders.packing;

/**
 * 추천 후보 박스. 부피 비교는 내치수 기준.
 */
public record CatalogBox(long id, BoxSpec spec) {

    public long innerVolumeMm3() {
        return (long) spec.innerWidthMm() * spec.innerLengthMm() * spec.innerHeightMm();
    }
}
