package com.awesome.backend.common.storage;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 사진 업로드 전용 스레드 풀 (D-27).
 *
 * <p>업로드는 응답을 내보낸 뒤에 돈다. 요청 처리 스레드와 같은 풀을 쓰면 업로드가 밀릴 때
 * 촬영 응답까지 같이 밀리므로 풀을 나눈다. 스레드 이름이 {@code image-upload-} 라서
 * 로그만 봐도 어느 쪽에서 난 일인지 갈린다.
 */
@Configuration
@EnableAsync
public class ImageUploadExecutorConfig {

    public static final String EXECUTOR = "imageUploadExecutor";

    /** 촬영 1회가 사진 3장이라 코어를 3 으로 둔다 — 한 촬영분은 서로 기다리지 않는다. */
    private static final int CORE_POOL_SIZE = 3;
    private static final int MAX_POOL_SIZE = 6;
    /** 큐가 차면 업로드 작업이 거절되고 사진은 PENDING 으로 남는다. 응답은 영향받지 않는다. */
    private static final int QUEUE_CAPACITY = 2_000;

    @Bean(name = EXECUTOR)
    public Executor imageUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("image-upload-");
        // 종료 시 올라가던 사진은 마저 올린다 — 여기서 끊으면 보관소에 없는 키가 DB 에 남는다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
