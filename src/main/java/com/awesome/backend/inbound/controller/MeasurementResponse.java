package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.entity.MeasurementImage;
import com.awesome.backend.inbound.entity.MeasurementSession;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 촬영·추론 응답 (02 §1-3). 성공/실패가 같은 200 응답이고 모양만 다르다 —
 * 프론트가 HTTP 에러가 아니라 {@code status} 로 분기하도록 계약돼 있다.
 *
 * <p>실패 응답에서 빠지는 필드(inferred·confidence·images 등)는 {@code NON_NULL} 로 생략된다.
 * 다만 {@code weightKg} 는 저울 미수신을 null 로 표현하기로 돼 있어 항상 실어 보낸다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeasurementResponse(
        Long sessionId,
        String status,
        Dimensions inferred,

        @JsonInclude(JsonInclude.Include.ALWAYS)
        BigDecimal weightKg,

        BigDecimal confidence,
        Boolean gatePassed,
        List<String> gateFailReasons,
        List<Image> images,
        HandlingDefaults handlingDefaults,
        String failReason) {

    public record Dimensions(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm) {
    }

    public record Image(Short cameraNo, String url) {

        static Image from(MeasurementImage image) {
            return new Image(image.getCameraNo(), image.getFilePath());
        }
    }

    /** category_attribute_map 기본값. 행이 없는 분류는 전부 false 다. */
    public record HandlingDefaults(boolean refrigerate, boolean fragile, boolean irregular) {
    }

    public static MeasurementResponse inferred(MeasurementSession session, HandlingDefaults handlingDefaults) {
        return new MeasurementResponse(
                session.getId(),
                session.getStatus().name(),
                new Dimensions(session.getInferredWidthCm(), session.getInferredLengthCm(),
                        session.getInferredHeightCm()),
                session.getMeasuredWeightKg(),
                session.getConfidence(),
                session.isGatePassed(),
                session.getGateFailReasons(),
                session.getImages().stream().map(Image::from).toList(),
                handlingDefaults,
                null);
    }

    public static MeasurementResponse failed(MeasurementSession session, String failReason) {
        return new MeasurementResponse(session.getId(), session.getStatus().name(), null,
                session.getMeasuredWeightKg(), null, null, null, null, null, failReason);
    }
}
