package com.awesome.backend.inbound.controller;

import com.awesome.backend.inbound.repository.CategoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 분류 조회 (docs/02-api-spec.md 1-7). */
@Tag(name = "분류")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Operation(summary = "분류 목록", description = "대분류·중분류 전체. 수기 등록(1-2) 드롭다운용.")
    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAllByOrderByCodeAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
