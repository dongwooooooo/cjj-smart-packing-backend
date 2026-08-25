package com.awesome.backend.demo.controller;

import com.awesome.backend.demo.service.DemoResetService;
import com.awesome.backend.demo.service.DemoResetSummary;
import com.awesome.backend.demo.service.DemoStatus;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시연 운영용 (명세 §4). 화면에서 부르지 않고 Swagger에서 눌러 쓴다. */
@RestController
@RequestMapping("/api/v1/admin/demo")
public class DemoAdminController {

    private final DemoResetService demoResetService;

    public DemoAdminController(DemoResetService demoResetService) {
        this.demoResetService = demoResetService;
    }

    @Operation(summary = "시연 리셋",
            description = "지난 시연에서 만들어진 주문·배송단위·토트 할당·측정 세션·재고 원장을 "
                    + "모두 지우고, demo/data의 상품과 출고지시로 시연 시작 상태를 다시 만든다. "
                    + "기준정보(분류·지역·라인·박스·토트)는 지우지 않고 상태만 되돌린다. "
                    + "몇 번을 눌러도 같은 상태가 된다.")
    @PostMapping("/reset")
    public DemoResetSummary reset() {
        return demoResetService.reset();
    }

    @Operation(summary = "시연 상태 조회",
            description = "지금 시연이 어디까지 왔는지 본다. 풀별 상품(치수·재고), 대기열 배치와 "
                    + "투입 여부, 토트·박스 현황, 접수된 주문·배송단위 수를 돌려준다. "
                    + "summary 항목만 읽어도 상태를 알 수 있다.")
    @GetMapping("/status")
    public DemoStatus status() {
        return demoResetService.status();
    }
}
