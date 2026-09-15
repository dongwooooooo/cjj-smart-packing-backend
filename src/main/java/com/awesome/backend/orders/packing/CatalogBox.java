package com.awesome.backend.orders.packing;

/**
 * 추천 후보 박스. 부피 비교는 내치수 기준, 요금·하드 제약 판정은 외치수 기준이다.
 *
 * <p>택배사가 재는 세 변의 합과 최장변은 박스 겉면이라 내치수로 판정하면 한 구간 낮게
 * 나온다. 외치수 = 내치수 + 판두께×2 (마주 보는 두 면).
 *
 * @param tareWeightKg 박스 자체 무게. 배송단위 총무게 = Σ상품 무게 + 이 값.
 */
public record CatalogBox(long id, BoxSpec spec, double tareWeightKg,
                         int outerWidthMm, int outerLengthMm, int outerHeightMm) {

    public static CatalogBox of(long id, BoxSpec spec, double tareWeightKg, double boardThicknessCm) {
        int pad = (int) Math.round(boardThicknessCm * 10) * 2;
        return new CatalogBox(id, spec, tareWeightKg,
                spec.innerWidthMm() + pad, spec.innerLengthMm() + pad, spec.innerHeightMm() + pad);
    }

    public long innerVolumeMm3() {
        return (long) spec.innerWidthMm() * spec.innerLengthMm() * spec.innerHeightMm();
    }

    /** 택배 요금표의 "세 변의 합". */
    public double outerSumCm() {
        return (outerWidthMm + outerLengthMm + outerHeightMm) / 10.0;
    }

    public double outerLongestCm() {
        return Math.max(outerWidthMm, Math.max(outerLengthMm, outerHeightMm)) / 10.0;
    }
}
