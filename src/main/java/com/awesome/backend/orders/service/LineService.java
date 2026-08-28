package com.awesome.backend.orders.service;

import com.awesome.backend.orders.controller.LineListResponse.LineResponse;
import com.awesome.backend.orders.entity.Line;
import com.awesome.backend.orders.repository.LineRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포장 라인 목록 조회.
 *
 * <p>포장 화면이 라인 선택 칸을 채울 때 쓴다. 이름을 화면에 박아 두면 이름이 바뀔 때마다 화면을
 * 고쳐야 하므로 서버가 알려준다.
 *
 * <p>식별자 오름차순으로 내려보낸다 — 화면의 라인 세 칸이 늘 같은 순서로 보여야 한다.
 */
@Service
@Transactional(readOnly = true)
public class LineService {

    private final LineRepository lineRepository;

    public LineService(LineRepository lineRepository) {
        this.lineRepository = lineRepository;
    }

    public List<LineResponse> findAll() {
        return lineRepository.findAllByOrderByIdAsc().stream()
                .map(LineService::toResponse)
                .toList();
    }

    private static LineResponse toResponse(Line line) {
        return new LineResponse(line.id(), line.name(), line.regionCode(), line.status().name());
    }
}
