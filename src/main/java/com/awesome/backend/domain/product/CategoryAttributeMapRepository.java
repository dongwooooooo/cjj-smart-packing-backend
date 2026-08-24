package com.awesome.backend.domain.product;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryAttributeMapRepository extends JpaRepository<CategoryAttributeMap, Long> {

    /** 촬영 응답의 handlingDefaults 를 채운다. 행이 없는 분류는 전부 false 로 취급한다. */
    Optional<CategoryAttributeMap> findByMediumCategoryCode(String mediumCategoryCode);
}
