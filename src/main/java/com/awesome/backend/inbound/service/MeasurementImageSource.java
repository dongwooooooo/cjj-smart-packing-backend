package com.awesome.backend.inbound.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.demo.service.DemoDataProperties;
import com.awesome.backend.inbound.entity.Product;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 촬영 이미지 3장의 출처 (docs/demo-subsystem-spec.md §6 P1 접점).
 *
 * <p>시연 환경에 카메라가 없어 촬영 대신 데이터셋 사진을 쓴다 — P3 데모 서브시스템이
 * {@code demo_product.image_dir} 아래 {@code cam1~3.jpg} 로 넣어 둔 것이다. 실물 촬영이 붙으면
 * 이 클래스만 바뀐다.
 *
 * <p>응답 URL 은 {@code /files/m/{gtin}/cam{n}.jpg} 이며 {@link MeasurementImageResourceConfig} 가
 * 같은 디렉토리를 정적으로 서빙한다.
 */
@Component
public class MeasurementImageSource {

    private static final Logger log = LoggerFactory.getLogger(MeasurementImageSource.class);

    /** 촬영함 고정 카메라 대수. measurement_image 의 camera_no CHECK 제약과 같은 값이다. */
    static final short CAMERA_COUNT = 3;

    static final String URL_PREFIX = "/files/m/";

    private final DemoProductRepository demoProducts;
    private final Path imagesRoot;

    public MeasurementImageSource(DemoProductRepository demoProducts, DemoDataProperties properties) {
        this.demoProducts = demoProducts;
        this.imagesRoot = imagesRoot(properties);
    }

    /** {@code demo.data-dir/images} — 응답 URL 은 이 디렉토리 기준 상대 경로다. */
    static Path imagesRoot(DemoDataProperties properties) {
        return Path.of(properties.dataDir()).resolve("images").toAbsolutePath().normalize();
    }

    /**
     * 카메라 3대분 사진. 데모 행이 없거나 파일이 하나라도 빠지면 빈 리스트다 —
     * 모델이 3장 미만을 거부하므로 부분 목록은 의미가 없다.
     */
    public List<CameraImage> load(Product product) {
        String imageDir = demoProducts.findById(product.gtin())
                .map(DemoProduct::imageDir)
                .orElse(null);
        if (imageDir == null) {
            log.info("데모 이미지가 없는 상품이다. gtin={}", product.gtin());
            return List.of();
        }

        Path dir = Path.of(imageDir).isAbsolute()
                ? Path.of(imageDir)
                : imagesRoot.getParent().resolve(imageDir).toAbsolutePath().normalize();

        List<CameraImage> images = new ArrayList<>();
        for (short cameraNo = 1; cameraNo <= CAMERA_COUNT; cameraNo++) {
            Path file = dir.resolve("cam" + cameraNo + ".jpg");
            try {
                images.add(new CameraImage(cameraNo, Files.readAllBytes(file), urlOf(file)));
            } catch (IOException e) {
                log.warn("데모 이미지를 읽지 못했다. gtin={} file={}", product.gtin(), file, e);
                return List.of();
            }
        }
        return images;
    }

    /** 이미지 루트 밖의 파일은 정적 서빙 범위 밖이라 URL 을 만들 수 없다 — 그 경우 파일 경로를 그대로 둔다. */
    private String urlOf(Path file) {
        if (!file.startsWith(imagesRoot)) {
            return file.toString();
        }
        return URL_PREFIX + imagesRoot.relativize(file).toString().replace('\\', '/');
    }
}
