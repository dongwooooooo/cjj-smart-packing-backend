package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.entity.Category;

/**
 * 분류 목록 응답 (docs/02-api-spec.md 1-7).
 * 프론트가 parentCode 로 트리를 구성하므로 계층 없이 평면 리스트로 내려준다.
 */
public record CategoryResponse(String code, String name, String level, String parentCode) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getCode(),
                category.getName(),
                category.getLevel().name(),
                category.getParent() == null ? null : category.getParent().getCode());
    }
}
