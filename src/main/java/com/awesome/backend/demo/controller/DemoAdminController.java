package com.awesome.backend.demo.controller;

import com.awesome.backend.demo.service.DemoAutoFeeder;
import com.awesome.backend.demo.service.DemoNextResult;
import com.awesome.backend.demo.service.DemoBarcodeFeeder;
import com.awesome.backend.demo.service.DemoInferenceWarmup;
import com.awesome.backend.demo.service.DemoOrderFeeder;
import com.awesome.backend.demo.service.DemoResetService;
import com.awesome.backend.demo.service.DemoToteFeeder;
import com.awesome.backend.demo.service.DemoResetSummary;
import com.awesome.backend.demo.service.DemoStatus;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시연 운영용 (명세 §4). 화면에서 부르지 않고 Swagger에서 눌러 쓴다. */
@RestController
@RequestMapping("/api/v1/admin/demo")
public class DemoAdminController {

    private final DemoResetService demoResetService;
    private final DemoOrderFeeder demoOrderFeeder;
    private final DemoAutoFeeder demoAutoFeeder;
    private final DemoInferenceWarmup warmup;
    private final DemoBarcodeFeeder barcodeFeeder;
    private final DemoToteFeeder toteFeeder;

    public DemoAdminController(DemoResetService demoResetService, DemoOrderFeeder demoOrderFeeder,
                               DemoAutoFeeder demoAutoFeeder,
                               DemoInferenceWarmup warmup, DemoBarcodeFeeder barcodeFeeder,
                               DemoToteFeeder toteFeeder) {
        this.demoResetService = demoResetService;
        this.demoOrderFeeder = demoOrderFeeder;
        this.demoAutoFeeder = demoAutoFeeder;
        this.warmup = warmup;
        this.barcodeFeeder = barcodeFeeder;
        this.toteFeeder = toteFeeder;
    }

    @Operation(summary = "시연 리셋",
            description = "지난 시연에서 만들어진 주문·배송단위·토트 할당·측정 세션·재고 원장을 "
                    + "모두 지우고, demo/data의 상품과 출고지시로 시연 시작 상태를 다시 만든다. "
                    + "기준정보(분류·지역·라인·박스·토트)는 지우지 않고 상태만 되돌린다. "
                    + "몇 번을 눌러도 같은 상태가 된다.")
    @PostMapping("/reset")
    public DemoResetSummary reset() {
        DemoResetSummary summary = demoResetService.reset();
        // 워밍은 리셋 트랜잭션 밖에서 — Lambda 콜드 스타트가 10초라 안에서 부르면
        // 그동안 DB 커넥션을 잡는다. 실패해도 리셋은 성공이다.
        return warmup.warmUp()
                .map(summary::withExtraSummaryLine)
                .orElse(summary);
    }

    @Operation(summary = "다음 시연 바코드",
            description = "입고 시연에서 다음에 스캔할 바코드를 하나 내준다. 시연장에 스캐너가 없어 "
                    + "화면의 버튼이 이 API 로 바코드를 받아 스캔 칸을 채운다. 아직 치수가 확정되지 "
                    + "않은 입고 풀 상품을 바코드 순으로 주며, 다 쓰면 204. 리셋하면 처음부터 다시 준다.")
    @PostMapping("/inbound/next-barcode")
    public ResponseEntity<DemoBarcodeFeeder.DemoBarcode> nextBarcode() {
        return barcodeFeeder.next()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "시연 상태 조회",
            description = "지금 시연이 어디까지 왔는지 본다. 풀별 상품(치수·재고), 대기열 배치와 "
                    + "투입 여부, 토트·박스 현황, 접수된 주문·배송단위 수를 돌려준다. "
                    + "summary 항목만 읽어도 상태를 알 수 있다.")
    @GetMapping("/status")
    public DemoStatus status() {
        return demoResetService.status();
    }

    @Operation(summary = "출고지시 한 건 투입",
            description = "대기열 맨 앞 배치를 꺼내 접수한다. 응답에 접수 결과와 남은 배치 수가 "
                    + "함께 들어 있다. 대기열이 비어 있으면 204를 돌려준다. "
                    + "접수가 실패하면 그 배치는 대기열에 그대로 남는다.")
    @PostMapping("/orders/next")
    public ResponseEntity<DemoNextResult> next() {
        Optional<DemoNextResult> result = demoOrderFeeder.feedNext();
        return result.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "자동 투입 시작",
            description = "정해진 간격(1~600초)으로 배치를 하나씩 자동 투입한다. 대기열이 비거나 "
                    + "투입이 실패하면 스스로 멈춘다. 이미 돌고 있으면 409 — 간격을 바꾸려면 "
                    + "멈춘 뒤 다시 시작한다. 서버를 다시 띄우면 꺼진 상태로 시작한다.")
    @PostMapping("/orders/auto")
    public void startAuto(@RequestParam int intervalSeconds) {
        demoAutoFeeder.start(intervalSeconds);
    }

    @Operation(summary = "자동 투입 정지",
            description = "자동 투입을 멈춘다. 돌고 있지 않아도 오류가 아니다.")
    @DeleteMapping("/orders/auto")
    public void stopAuto() {
        demoAutoFeeder.stop();
    }

    @Operation(summary = "다음 시연 토트 바코드",
            description = "포장 시연에서 지정한 라인의 다음 토트 바코드를 하나 내준다. 시연장에 "
                    + "스캐너가 없어 화면의 버튼이 이 통로로 바코드를 받아 스캔 칸을 채운다. "
                    + "아직 포장이 끝나지 않고 토트가 붙은 배송단위를 만들어진 순서대로 주며, "
                    + "한 번 내준 토트는 다시 나오지 않는다. 더 없으면 204. 리셋하면 처음부터 다시 준다.")
    @PostMapping("/outbound/next-tote")
    public ResponseEntity<DemoToteFeeder.DemoTote> nextTote(@RequestParam Long lineId) {
        return toteFeeder.next(lineId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
