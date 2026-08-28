package com.awesome.backend.orders.controller;

import java.util.List;

/**
 * GET /api/v1/lines 응답 래퍼 — {@code {"lines": [...]}}.
 *
 * <p>{@code status}를 함께 내려 화면이 판단하게 한다. 서버가 운영 중인 라인만 걸러 내보내면
 * 시연 도중 라인 하나가 멈췄을 때 목록에서 사라져 버리는데, 포장 화면은 라인 세 칸이 늘 같은
 * 자리에 있어야 한다. 멈춘 라인은 자리를 지키되 고를 수 없게 보이는 편이 낫다.
 */
public record LineListResponse(List<LineResponse> lines) {

    public record LineResponse(Long lineId, String name, String regionCode, String status) {
    }
}
