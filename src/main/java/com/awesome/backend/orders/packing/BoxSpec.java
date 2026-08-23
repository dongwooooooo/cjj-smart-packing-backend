package com.awesome.backend.orders.packing;

/**
 * 박스 내치수. 내부 단위 mm.
 */
public record BoxSpec(int innerWidthMm, int innerLengthMm, int innerHeightMm) {

    public static BoxSpec ofCm(double widthCm, double lengthCm, double heightCm) {
        return new BoxSpec(toMm(widthCm), toMm(lengthCm), toMm(heightCm));
    }

    private static int toMm(double cm) {
        return (int) Math.round(cm * 10);
    }
}
