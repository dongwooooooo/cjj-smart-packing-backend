package com.awesome.backend.outbound.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.entity.ToteAssignment;
import com.awesome.backend.outbound.repository.ToteAssignmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토트 할당 (명세 §6). 배송단위 1개당 유휴 토트 1개.
 *
 * <p>토트가 모자란 상황은 시연 범위에서 가정하지 않는다 — 발생하면 배치 전체를
 * 되돌리고 500으로 끝낸다. 일부만 접수된 채로 남기는 것보다 통째로 실패하는 게 낫다.
 */
@Service
public class ToteAllocator {

    private final ToteRepository toteRepository;
    private final ToteAssignmentRepository toteAssignmentRepository;

    public ToteAllocator(ToteRepository toteRepository,
                         ToteAssignmentRepository toteAssignmentRepository) {
        this.toteRepository = toteRepository;
        this.toteAssignmentRepository = toteAssignmentRepository;
    }

    /**
     * 유휴 토트를 ID 오름차순으로 하나 잡아 배송단위에 묶는다.
     *
     * <p>같은 트랜잭션에서 연달아 부르면 앞서 잡은 토트는 조회에서 빠진다 —
     * 조회 전에 변경분이 flush되기 때문이다.
     */
    @Transactional
    public void allocate(long shipmentId) {
        Tote tote = toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "유휴 토트가 없어 출고지시를 접수하지 못했습니다."));
        tote.assign();
        toteAssignmentRepository.save(new ToteAssignment(tote.id(), shipmentId));
    }
}
