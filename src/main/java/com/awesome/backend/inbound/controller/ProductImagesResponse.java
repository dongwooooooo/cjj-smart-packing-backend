package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.entity.MeasurementImage;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 제품 원본 이미지 응답 (02 §1-6). 출고 포장 화면(P2)이 제품을 클릭했을 때 쓴다.
 *
 * @param source     MEASUREMENT = 확정 세션의 촬영 3장, MASTER_FALLBACK = 코리안넷 이미지 1장
 * @param images     MASTER_FALLBACK 이면 1장이고 cameraNo 가 null 이다
 */
public record ProductImagesResponse(String source, List<Image> images) {

    public static final String SOURCE_MEASUREMENT = "MEASUREMENT";
    public static final String SOURCE_MASTER_FALLBACK = "MASTER_FALLBACK";

    /** @param cameraNo 촬영 이미지는 1~3, 마스터 대체 이미지는 null */
    public record Image(Short cameraNo, String url) {
    }

    /** 촬영본은 보관소 키로만 저장돼 있어 조회 주소를 여기서 발급한다 (D-25). */
    public static ProductImagesResponse ofMeasurement(List<MeasurementImage> images,
                                                     UnaryOperator<String> urlOf) {
        return new ProductImagesResponse(SOURCE_MEASUREMENT,
                images.stream()
                        .map(image -> new Image(image.getCameraNo(), urlOf.apply(image.getFilePath())))
                        .toList());
    }

    public static ProductImagesResponse ofMasterFallback(String imageUrl) {
        return new ProductImagesResponse(SOURCE_MASTER_FALLBACK,
                List.of(new Image(null, imageUrl)));
    }
}
