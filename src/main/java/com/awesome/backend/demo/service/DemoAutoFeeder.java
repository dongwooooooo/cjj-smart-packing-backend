package com.awesome.backend.demo.service;

import com.awesome.backend.common.error.ApiException;
import com.awesome.backend.common.error.ErrorCode;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 정해진 간격으로 배치를 자동 투입한다 (명세 §5). 시연 중 손을 대지 않아도
 * 화면이 계속 움직이게 하는 용도다.
 *
 * <p>상태는 메모리에만 있다 — 서버를 다시 띄우면 꺼진 상태로 시작한다.
 * 시연이 끝나면 프로세스와 함께 사라지는 게 맞다고 봤다.
 *
 * <p>대기열이 비거나 투입이 실패하면 스스로 멈춘다. 빈 큐를 계속 두드리거나
 * 같은 실패를 반복하는 것보다, 멈춰서 사람이 보게 하는 편이 낫다.
 */
@Service
public class DemoAutoFeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAutoFeeder.class);
    private static final int MIN_INTERVAL_SECONDS = 1;
    private static final int MAX_INTERVAL_SECONDS = 600;

    private final DemoOrderFeeder feeder;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "demo-auto-feeder");
                thread.setDaemon(true);
                return thread;
            });

    private ScheduledFuture<?> running;
    private int intervalSeconds;

    public DemoAutoFeeder(DemoOrderFeeder feeder) {
        this.feeder = feeder;
    }

    public synchronized void start(int intervalSeconds) {
        if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "간격은 %d~%d초 사이여야 합니다.".formatted(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
                    Map.of("intervalSeconds", intervalSeconds));
        }
        if (isRunning()) {
            // 조용히 덮어쓰면 지금 몇 초 간격으로 도는지 알 수 없게 된다
            throw new ApiException(ErrorCode.INVALID_STATE,
                    "자동 투입이 이미 %d초 간격으로 돌고 있습니다. 멈춘 뒤 다시 시작하세요."
                            .formatted(this.intervalSeconds),
                    Map.of("intervalSeconds", this.intervalSeconds));
        }
        this.intervalSeconds = intervalSeconds;
        this.running = scheduler.scheduleWithFixedDelay(this::feedOnce,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("자동 투입 시작 — {}초 간격", intervalSeconds);
    }

    public synchronized void stop() {
        if (running != null) {
            running.cancel(false);
            running = null;
            log.info("자동 투입 정지");
        }
    }

    public synchronized boolean isRunning() {
        return running != null && !running.isCancelled();
    }

    public synchronized Integer intervalSeconds() {
        return isRunning() ? intervalSeconds : null;
    }

    private void feedOnce() {
        try {
            if (feeder.feedNext().isEmpty()) {
                log.info("대기열이 비어 자동 투입을 멈춥니다.");
                stop();
            }
        } catch (RuntimeException e) {
            log.warn("자동 투입이 실패해 멈춥니다 — 배치는 대기열에 남아 있습니다: {}", e.getMessage());
            stop();
        }
    }
}
