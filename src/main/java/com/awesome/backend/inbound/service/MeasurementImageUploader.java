package com.awesome.backend.inbound.service;

import com.awesome.backend.common.storage.ImageUploadExecutorConfig;
import com.awesome.backend.common.storage.StorageProperties;
import com.awesome.backend.common.metrics.StageTimers;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 촬영 사진을 보관소에 올린다 (D-27). 세션이 커밋된 뒤, 응답과 무관하게 돈다.
 *
 * <p>전에는 {@link MeasurementWriter} 가 트랜잭션 안에서 사진 3장을 동기로 올렸다. 운영은 S3 라
 * 그 시간이 DB 커넥션을 잡은 채 흘렀고, 촬영 응답도 그만큼 늦었다. 지금은 세션이 키만 정해
 * 커밋하고, 커밋 뒤에 이 클래스가 전용 스레드에서 올린다.
 *
 * <p>올리다 실패하면 설정한 횟수만큼 더 해 보고, 그래도 안 되면 WARN 을 남기고 해당 행을
 * FAILED 로 바꾼다 — 실패를 삼키면 보관소에 없는 키가 DB 에 남고 1-6 조회가 죽은 주소를 내보낸다.
 */
@Component
public class MeasurementImageUploader {

    private static final Logger log = LoggerFactory.getLogger(MeasurementImageUploader.class);

    private final MeasurementImageSource imageSource;
    private final MeasurementImageUploadState uploadState;
    private final StorageProperties.Upload upload;

    private final MeterRegistry registry;

    public MeasurementImageUploader(MeasurementImageSource imageSource,
                                    MeasurementImageUploadState uploadState,
                                    StorageProperties properties, MeterRegistry registry) {
        this.imageSource = imageSource;
        this.registry = registry;
        this.uploadState = uploadState;
        this.upload = properties.upload();
    }

    /**
     * 커밋 뒤에만 올린다. 롤백된 세션의 사진을 보관소에 남기지 않기 위해서다 —
     * 지우는 주체가 없어 그대로 쓰레기가 된다.
     */
    @Async(ImageUploadExecutorConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void upload(MeasurementImagesPending event) {
        long t0 = System.nanoTime();
        int failed = 0;
        for (MeasurementImagesPending.PendingImage image : event.images()) {
            if (!uploadOne(event.sessionId(), image)) {
                failed++;
            }
        }
        long uploadMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("upload.timing sessionId={} images={} failed={} uploadMs={}",
                event.sessionId(), event.images().size(), failed, uploadMs);
        StageTimers.record(registry, "upload.duration", "s3", failed == 0 ? "ok" : "failed",
                System.nanoTime() - t0);
    }

    /** 한 장. 올렸으면 true, 재시도까지 실패했으면 false. */
    private boolean uploadOne(Long sessionId, MeasurementImagesPending.PendingImage image) {
        int attempts = upload.retryCount() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                imageSource.put(image.key(), image.jpeg());
                uploadState.markStored(sessionId, image.cameraNo());
                return true;
            } catch (RuntimeException e) {
                if (attempt == attempts) {
                    log.warn("사진 업로드가 {}회 시도 끝에 실패했다. sessionId={} cameraNo={} key={}",
                            attempts, sessionId, image.cameraNo(), image.key(), e);
                    uploadState.markFailed(sessionId, image.cameraNo());
                    return false;
                }
                log.info("사진 업로드 재시도. sessionId={} cameraNo={} attempt={}/{} 사유={}",
                        sessionId, image.cameraNo(), attempt, attempts, e.toString());
                if (!sleepBeforeRetry()) {
                    uploadState.markFailed(sessionId, image.cameraNo());
                    return false;
                }
            }
        }
        return false;
    }

    /** 대기 중 인터럽트되면 더 시도하지 않는다 — 종료 중이라는 뜻이다. */
    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(upload.retryInterval().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
