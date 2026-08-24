package com.awesome.backend.outbound.application;

import com.awesome.backend.outbound.domain.BoxType;
import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/v1/box-types 응답 항목. docs/02-api-spec.md 3-4.
 */
public record BoxTypeResponse(
        Long boxTypeId,
        String name,
        List<BigDecimal> innerCm,
        int stockQty) {

    public static BoxTypeResponse from(BoxType boxType) {
        return new BoxTypeResponse(
                boxType.id(),
                boxType.name(),
                List.of(boxType.innerWidthCm(), boxType.innerLengthCm(), boxType.innerHeightCm()),
                boxType.stockQty());
    }
}
