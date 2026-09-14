package com.awesome.backend.inbound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.awesome.backend.common.storage.ImageStore;
import com.awesome.backend.common.storage.LocalImageStore;
import com.awesome.backend.inbound.entity.ImageUploadStatus;
import com.awesome.backend.inbound.entity.MeasurementImage;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.MeasurementImageRepository;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.support.DemoImageFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 사진 업로드가 트랜잭션 밖·응답 뒤로 빠졌는지 본다 (D-27).
 *
 * <p>테스트 트랜잭션을 쓰지 않는다 — 쓰면 커밋이 일어나지 않아 AFTER_COMMIT 리스너가 돌지 않는다.
 *
 * <p>보관소는 프로브로 갈아 끼운다. 읽기는 실제 로컬 보관소로 넘겨 촬영 입력 사진을 그대로 쓰고,
 * 쓰기만 가로채 호출 시점의 트랜잭션 여부·스레드 이름을 기록한다.
 */
@SpringBootTest(properties = {
        "storage.local-base-path=" + DemoImageFixture.BASE_PATH,
        // 재시도 간격은 테스트가 기다릴 시간이라 짧게 둔다. 횟수는 기본값(3회) 그대로다
        "storage.upload.retry-interval=20ms"
})
@Testcontainers
class MeasurementAsyncImageUploadIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String GTIN = "8801234500011";

    /** 쓰기를 가로채는 보관소. 읽기는 실제 로컬 보관소가 처리한다. */
    static class ProbeImageStore implements ImageStore {

        final ImageStore delegate =
                new LocalImageStore(Path.of(DemoImageFixture.BASE_PATH), "/files/m/");
        final ConcurrentLinkedQueue<String> writtenKeys = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> writeThreads = new ConcurrentLinkedQueue<>();
        final AtomicInteger writesInTransaction = new AtomicInteger();
        final AtomicInteger attemptsOnFailingKey = new AtomicInteger();
        volatile String failingKeySuffix;

        @Override
        public Optional<byte[]> read(String key) {
            return delegate.read(key);
        }

        @Override
        public void write(String key, byte[] jpeg) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                writesInTransaction.incrementAndGet();
            }
            writeThreads.add(Thread.currentThread().getName());
            if (failingKeySuffix != null && key.endsWith(failingKeySuffix)) {
                attemptsOnFailingKey.incrementAndGet();
                throw new IllegalStateException("보관소 장애 흉내");
            }
            writtenKeys.add(key);
            delegate.write(key, jpeg);
        }

        @Override
        public String url(String key) {
            return delegate.url(key);
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        ProbeImageStore probeImageStore() {
            return new ProbeImageStore();
        }
    }

    @Autowired MeasurementService measurementService;
    @Autowired ProductRepository productRepository;
    @Autowired MeasurementImageRepository imageRepository;
    @Autowired ProbeImageStore probe;
    @Autowired com.awesome.backend.demo.repository.DemoProductRepository demoProducts;

    private Long productId;

    @BeforeEach
    void setUp() {
        DemoImageFixture.create(demoProducts, GTIN);
        productId = productRepository.findByGtin(GTIN).map(Product::id).orElseThrow();
        probe.writtenKeys.clear();
        probe.writeThreads.clear();
        probe.writesInTransaction.set(0);
        probe.attemptsOnFailingKey.set(0);
        probe.failingKeySuffix = null;
    }

    private List<MeasurementImage> imagesOf(Long sessionId) {
        return imageRepository.findBySessionIdOrderByCameraNoAsc(sessionId);
    }

    @Test
    void 저장_직후_세션은_커밋돼_있고_업로드는_트랜잭션_밖에서_일어난다() {
        var response = measurementService.measure(productId);

        // 응답이 돌아온 시점에 세션과 사진 행은 이미 있다 — 업로드는 아직일 수 있다
        assertThat(imagesOf(response.sessionId())).hasSize(3);

        awaitUploaded(response.sessionId());
        assertThat(probe.writesInTransaction.get()).isZero();
        assertThat(probe.writeThreads).allSatisfy(
                name -> assertThat(name).startsWith("image-upload-"));
    }

    @Test
    void 커밋_후_사진_3장이_실제로_올라간다() {
        var response = measurementService.measure(productId);

        awaitUploaded(response.sessionId());

        assertThat(probe.writtenKeys).containsExactlyInAnyOrder(
                "measurements/%d/cam1.jpg".formatted(response.sessionId()),
                "measurements/%d/cam2.jpg".formatted(response.sessionId()),
                "measurements/%d/cam3.jpg".formatted(response.sessionId()));
        assertThat(imagesOf(response.sessionId()))
                .allSatisfy(image -> assertThat(image.getUploadStatus())
                        .isEqualTo(ImageUploadStatus.STORED));
    }

    @Test
    void 업로드가_계속_실패하면_재시도_뒤_실패_표시가_남는다() {
        probe.failingKeySuffix = "cam2.jpg";

        var response = measurementService.measure(productId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<MeasurementImage> images = imagesOf(response.sessionId());
            assertThat(images).hasSize(3);
            assertThat(images.get(1).getUploadStatus()).isEqualTo(ImageUploadStatus.FAILED);
            assertThat(images.get(0).getUploadStatus()).isEqualTo(ImageUploadStatus.STORED);
            assertThat(images.get(2).getUploadStatus()).isEqualTo(ImageUploadStatus.STORED);
        });
        // 기본 retry-count 3 → 최초 1회 + 재시도 3회
        assertThat(probe.attemptsOnFailingKey.get()).isEqualTo(4);
    }

    private void awaitUploaded(Long sessionId) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(imagesOf(sessionId))
                        .allSatisfy(image -> assertThat(image.getUploadStatus())
                                .isEqualTo(ImageUploadStatus.STORED)));
    }
}
