package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.Category;
import com.awesome.backend.inbound.entity.CategoryLevel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, String> {

    /** 수기 등록 드롭다운(1-7). 프론트가 parentCode 로 트리를 구성한다. */
    List<Category> findAllByOrderByCodeAsc();

    List<Category> findByLevel(CategoryLevel level);
}
