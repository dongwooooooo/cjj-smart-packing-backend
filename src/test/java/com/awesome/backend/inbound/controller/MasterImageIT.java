package com.awesome.backend.inbound.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.common.storage.ImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 스캔 1단에 뜨는 상품 사진(마스터 이미지)이 실제 사진을 가리켜야 한다.
 *
 * <p>코리안넷에서 받아 저장소에 올려 둔 사진을 쓴다. 사진이 없는 상품은 placeholder 로 남는다 —
 * 화면은 "no-image" 를 보여주면 되고, 그것 때문에 스캔이 실패하지는 않는다.
 */
/*
 * 이미지 보관소를 테스트 전용 디렉토리로 돌린다. 기본값은 demo/data 라서 그대로 두면 이 테스트가
 * 쓰는 가짜 사진이 실제 시연 사진을 덮어쓴다 — 3 바이트짜리로 잘린 사진이 저장소에 들어가
 * 병합된 적이 있고, 원인이 이것이었다.
 */
@SpringBootTest(properties = "storage.local-base-path=build/test-image-store")
@Testcontainers
class MasterImageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
    private static final String SCAN = "/api/v1/inbound/scans";

    @Autowired WebApplicationContext context;
    @Autowired DemoProductRepository demoProducts;
    @Autowired ImageStore imageStore;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    @Test
    void 마스터_사진이_있으면_그_주소를_돌려준다() throws Exception {
        String gtin = demoProducts.findByPool(DemoProduct.Pool.INBOUND).getFirst().gtin();
        imageStore.write("master/" + gtin + ".jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        MvcResult result = mvc.perform(post(SCAN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"" + gtin + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String imageUrl = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.product.imageUrl");
        assertThat(imageUrl).contains(gtin).doesNotContain("placehold");
    }

    @Test
    void 마스터_사진이_없으면_placeholder_로_남는다() throws Exception {
        // 코리안넷에 상품은 있지만 사진이 없는 경우가 실제로 있다. 저장소에 없는 상품을 골라
        // 그 상황을 그대로 확인한다.
        String gtin = demoProducts.findByPool(DemoProduct.Pool.INBOUND).stream()
                .map(DemoProduct::gtin)
                .filter(g -> imageStore.read("master/" + g + ".jpg").isEmpty())
                .findFirst()
                .orElseGet(() -> {
                    org.junit.jupiter.api.Assumptions.abort("사진 없는 입고 풀 상품이 없다");
                    return null;
                });

        MvcResult result = mvc.perform(post(SCAN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"" + gtin + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String imageUrl = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.product.imageUrl");
        assertThat(imageUrl).contains("placehold");
    }
}
