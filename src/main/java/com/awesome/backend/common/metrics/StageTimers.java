package com.awesome.backend.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;

/**
 * 구간 시간을 Micrometer 타이머로 남긴다. 로그의 measure.timing / inference.timing / upload.timing 과 같은 구간을
 * 같은 이름으로 내보내, 부하 테스트 때 Prometheus 에서 p50/p95/p99 를 볼 수 있게 한다.
 * 버킷(percentiles-histogram)은 application.yml 의 management.metrics.distribution 에서 이름별로 켠다.
 */
public final class StageTimers {

    private StageTimers() {
    }

    public static void record(MeterRegistry registry, String name, String stage, String outcome, long nanos) {
        Timer.builder(name)
                .description("구간 처리 시간")
                .tag("stage", stage)
                .tag("outcome", outcome)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
