package com.awesome.backend.inbound.service;

import com.awesome.backend.common.storage.ImageStore;
import com.awesome.backend.inbound.entity.Product;
import org.springframework.stereotype.Component;

/**
 * 스캔 1단에 뜨는 상품 사진(마스터 이미지)의 출처.
 *
 * <p>코리안넷 상품 조회에서 받아 {@code master/{gtin}.jpg} 로 올려 둔 사진을 쓴다. 시연 중
 * 코리안넷을 직접 부르지 않는 이유는 발표장 네트워크에 시연을 걸지 않기 위해서다.
 *
 * <p>사진이 없는 상품은 placeholder 로 남는다 — 화면은 "no-image" 를 보여주면 되고,
 * 그것 때문에 스캔이 실패하지는 않는다.
 */
@Component
public class MasterImageSource {

    static final String KEY_PREFIX = "master/";

    private final ImageStore imageStore;

    public MasterImageSource(ImageStore imageStore) {
        this.imageStore = imageStore;
    }

    /** 저장소에 사진이 있으면 그 조회 주소, 없으면 상품에 적힌 값 그대로. */
    public String urlFor(Product product) {
        String key = KEY_PREFIX + product.gtin() + ".jpg";
        if (imageStore.read(key).isEmpty()) {
            return product.imageUrl();
        }
        return imageStore.url(key);
    }
}
