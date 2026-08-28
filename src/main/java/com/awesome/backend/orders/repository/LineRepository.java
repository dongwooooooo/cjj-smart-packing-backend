package com.awesome.backend.orders.repository;

import com.awesome.backend.orders.entity.Line;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LineRepository extends JpaRepository<Line, Long> {

    /** 같은 지역 복수 라인이면 ID 오름차순 첫 라인 (명세 §5 임시 규칙). */
    List<Line> findByRegionCodeAndStatusOrderByIdAsc(String regionCode, Line.Status status);

    /** 화면의 라인 선택 칸용. 늘 같은 순서로 보이도록 ID 오름차순으로 내려보낸다. */
    List<Line> findAllByOrderByIdAsc();
}
