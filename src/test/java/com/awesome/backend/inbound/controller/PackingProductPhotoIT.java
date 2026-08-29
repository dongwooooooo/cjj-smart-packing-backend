package com.awesome.backend.inbound.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.common.storage.ImageStore;
import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 포장 화면의 제품 사진 — 출고 상품은 촬영을 거치지 않아 늘 대체 사진으로 온다.
 *
 * <p>이미지 보관소를 테스트 전용 디렉토리로 돌린다. 기본값은 demo/data 라서 그대로 두면 이
 * 테스트가 쓰는 사진이 실제 시연 사진을 덮어쓴다.
 */
@SpringBootTest(properties = "storage.local-base-path=build/test-image-store")
@Testcontainers
class PackingProductPhotoIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";

    @Autowired WebApplicationContext context;
    @Autowired DemoProductRepository demoProducts;
    @Autowired ProductRepository productRepository;
    @Autowired ImageStore imageStore;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    private Product firstOutbound() {
        String gtin = demoProducts.findByPool(DemoProduct.Pool.OUTBOUND).getFirst().gtin();
        return productRepository.findByGtin(gtin).orElseThrow();
    }

    /** 사진을 올리지 않은 상품. 보관소는 테스트 사이에 남으므로 다른 상품을 써야 섞이지 않는다. */
    private Product outboundWithoutPhoto() {
        String gtin = demoProducts.findByPool(DemoProduct.Pool.OUTBOUND).getLast().gtin();
        return productRepository.findByGtin(gtin).orElseThrow();
    }

    @Test
    void 출고_상품은_저장소에_올려_둔_사진을_돌려준다() throws Exception {
        Product product = firstOutbound();
        imageStore.write("master/" + product.gtin() + ".jpg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01});

        mvc.perform(get("/api/v1/products/" + product.id() + "/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0].url").value(
                        org.hamcrest.Matchers.containsString(product.gtin())))
                .andExpect(jsonPath("$.images[0].url").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("placehold"))));
    }

    @Test
    void 저장소에_사진이_없으면_상품에_적힌_주소로_남는다() throws Exception {
        // 사진을 못 구한 상품이 있어도 포장 화면이 실패하지는 않는다.
        Product product = outboundWithoutPhoto();

        mvc.perform(get("/api/v1/products/" + product.id() + "/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0].url").value(product.imageUrl()));

        assertThat(product.imageUrl()).isNotBlank();
    }
}
