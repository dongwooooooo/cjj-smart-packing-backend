package com.awesome.backend.orders.packing;

/**
 * 배치 대상 직육면체 블록. 내부 단위는 mm (0.1cm 정밀도의 정수 표현 — 부동소수 오차 회피).
 */
public record Block(int widthMm, int lengthMm, int heightMm) {

    public static Block ofCm(double widthCm, double lengthCm, double heightCm) {
        return new Block(toMm(widthCm), toMm(lengthCm), toMm(heightCm));
    }

    private static int toMm(double cm) {
        return (int) Math.round(cm * 10);
    }
}
