package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.entity.DemoOrderQueue;
import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 출고지시 한 건 투입 (명세 §5). 대기열 맨 앞 배치를 꺼내 접수한다.
 * 시연 중에는 이 버튼을 눌러 화면이 하나씩 채워지는 걸 보여준다.
 */
@SpringBootTest
@Testcontainers
@Transactional
class DemoNextIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
    private static final String NEXT = "/api/v1/admin/demo/orders/next";

    @Autowired WebApplicationContext context;
    @Autowired DemoOrderQueueRepository queueRepository;
    @Autowired OrderRepository orderRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    @Test
    void 맨_앞_배치를_접수하고_남은_수를_알려준다() throws Exception {
        mvc.perform(post(NEXT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.remaining").value(2))
                .andExpect(jsonPath("$.batchId").value("DEMO-1"))
                .andExpect(jsonPath("$.orders").value(1))
                .andExpect(jsonPath("$.shipments").value(1))
                .andExpect(jsonPath("$.rejected").isEmpty());

        assertThat(orderRepository.existsByReceiptNo("R-DEMO-0001")).isTrue();
    }

    @Test
    void 꺼낸_배치는_투입됨으로_남는다() throws Exception {
        mvc.perform(post(NEXT)).andExpect(status().isOk());

        List<DemoOrderQueue> queued = queueRepository.findAllByOrderBySeqAsc();
        assertThat(queued.get(0).releasedAt()).isNotNull();
        assertThat(queued.get(1).releasedAt()).isNull();
        assertThat(queueRepository.countByReleasedAtIsNull()).isEqualTo(2);
    }

    @Test
    void 부를_때마다_다음_배치로_넘어간다() throws Exception {
        mvc.perform(post(NEXT)).andExpect(jsonPath("$.seq").value(1));
        mvc.perform(post(NEXT)).andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.splitOrders").value(1));
        mvc.perform(post(NEXT)).andExpect(jsonPath("$.seq").value(3))
                .andExpect(jsonPath("$.remaining").value(0));

        assertThat(queueRepository.countByReleasedAtIsNull()).isZero();
        assertThat(orderRepository.count()).isEqualTo(3);
    }

    @Test
    void 대기열이_비면_내용_없음을_돌려준다() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(NEXT)).andExpect(status().isOk());
        }

        mvc.perform(post(NEXT)).andExpect(status().isNoContent());
    }
}
