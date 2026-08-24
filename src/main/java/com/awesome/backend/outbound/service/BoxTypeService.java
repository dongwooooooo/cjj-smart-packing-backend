package com.awesome.backend.outbound.service;

import com.awesome.backend.outbound.controller.BoxTypeResponse;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 박스 타입 조회. 쓰기가 없는 단순 목록 조회이므로 클래스 전체를 readOnly 트랜잭션으로 묶는다.
 */
@Service
@Transactional(readOnly = true)
public class BoxTypeService {

    private final BoxTypeRepository boxTypeRepository;

    public BoxTypeService(BoxTypeRepository boxTypeRepository) {
        this.boxTypeRepository = boxTypeRepository;
    }

    public List<BoxTypeResponse> findAll() {
        return boxTypeRepository.findAll(Sort.by("id").ascending()).stream()
                .map(BoxTypeResponse::from)
                .toList();
    }
}
