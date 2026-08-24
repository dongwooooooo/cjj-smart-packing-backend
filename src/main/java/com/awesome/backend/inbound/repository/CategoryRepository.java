package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 분류 조회. 목록 조회(1-7)는 D-21 로 삭제됐고, 남은 용도는 1-1 응답의 분류명 표시를 위한
 * 코드 단건 조회(findById)뿐이다 — 작업자가 분류를 고르는 UI 는 없다.
 */
public interface CategoryRepository extends JpaRepository<Category, String> {
}
