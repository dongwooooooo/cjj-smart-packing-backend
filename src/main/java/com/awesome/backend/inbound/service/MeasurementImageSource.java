package com.awesome.backend.inbound.service;

import com.awesome.backend.common.storage.ImageStore;
import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 촬영 이미지의 출처와 보관 (docs/demo-subsystem-spec.md §6, D-25).
 *
 * <p>시연 환경에 카메라가 없어 촬영 대신 데이터셋 사진을 쓴다 — 보관소의
 * {@code images/{gtin}/cam{n}.jpg} 를 읽는다. 실물 촬영이 붙으면 이 클래스의 읽는 쪽만 바뀐다.
 *
 * <p>추론이 끝난 사진은 세션 키로 다시 저장한다. 재촬영하면 세션이 달라지므로 이전 촬영본이
 * 덮이지 않고, 1-6 제품 이미지 조회가 확정 세션의 사진을 그대로 돌려줄 수 있다.
 */
@Component
public class MeasurementImageSource {

    private static final Logger log = LoggerFactory.getLogger(MeasurementImageSource.class);

    /** 촬영함 고정 카메라 대수. measurement_image 의 camera_no CHECK 제약과 같은 값이다. */
    static final short CAMERA_COUNT = 3;

    private final DemoProductRepository demoProducts;
    private final ImageStore imageStore;

    public MeasurementImageSource(DemoProductRepository demoProducts, ImageStore imageStore) {
        this.demoProducts = demoProducts;
        this.imageStore = imageStore;
    }

    /**
     * 카메라 3대분 사진. 데모 행이 없거나 사진이 하나라도 빠지면 빈 리스트다 —
     * 모델이 3장 미만을 거부하므로 부분 목록은 의미가 없다.
     */
    public List<CameraImage> load(Product product) {
        Optional<String> prefix = demoProducts.findById(product.gtin()).map(DemoProduct::imageDir);
        if (prefix.isEmpty() || prefix.get() == null) {
            log.info("데모 이미지가 없는 상품이다. gtin={}", product.gtin());
            return List.of();
        }

        List<CameraImage> images = new ArrayList<>();
        for (short cameraNo = 1; cameraNo <= CAMERA_COUNT; cameraNo++) {
            String key = "%s/cam%d.jpg".formatted(prefix.get(), cameraNo);
            Optional<byte[]> jpeg = imageStore.read(key);
            if (jpeg.isEmpty()) {
                log.warn("촬영 이미지를 찾지 못했다. gtin={} key={}", product.gtin(), key);
                return List.of();
            }
            images.add(new CameraImage(cameraNo, jpeg.get(), key));
        }
        return images;
    }

    /** 세션에 귀속되는 키로 저장하고 그 키를 돌려준다. DB 에는 조회 주소가 아니라 이 키가 남는다. */
    public String store(Long sessionId, CameraImage image) {
        String key = "measurements/%d/cam%d.jpg".formatted(sessionId, image.cameraNo());
        imageStore.write(key, image.jpeg());
        return key;
    }

    /** 저장된 키를 화면이 쓸 조회 주소로 바꾼다. */
    public String url(String key) {
        return imageStore.url(key);
    }
}
