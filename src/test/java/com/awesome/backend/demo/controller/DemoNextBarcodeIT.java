package com.awesome.backend.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import java.util.ArrayList;
import java.util.List;
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
 * 입고 시연은 바코드 스캐너 없이 돈다. 화면의 버튼이 이 API 를 눌러 다음 상품 바코드를
 * 받아가고, 그 값으로 스캔·촬영·확정을 진행한다 (demo/scenario.md 입고 장면).
 *
 * <p>순서를 서버가 들고 있어야 새로고침하거나 다시 시연해도 어긋나지 않는다.
 */
@SpringBootTest
@Testcontainers
class DemoNextBarcodeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6");

    private static final String RESET = "/api/v1/admin/demo/reset";
    private static final String NEXT_BARCODE = "/api/v1/admin/demo/inbound/next-barcode";

    @Autowired WebApplicationContext context;
    @Autowired DemoProductRepository demoProducts;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post(RESET)).andExpect(status().isOk());
    }

    @Test
    void 입고_풀_상품을_순서대로_돌려준다() throws Exception {
        List<String> expected = demoProducts.findByPool(DemoProduct.Pool.INBOUND).stream()
                .map(DemoProduct::gtin)
                .sorted()
                .toList();

        List<String> got = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            MvcResult result = mvc.perform(post(NEXT_BARCODE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.barcode").exists())
                    .andExpect(jsonPath("$.name").exists())
                    .andReturn();
            got.add(com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), "$.barcode"));
        }

        assertThat(got).containsExactlyElementsOf(expected);
    }

    @Test
    void 남은_수를_함께_알려준다() throws Exception {
        int total = demoProducts.findByPool(DemoProduct.Pool.INBOUND).size();

        mvc.perform(post(NEXT_BARCODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(total - 1));
    }

    @Test
    void 다_쓰면_내용_없음을_돌려준다() throws Exception {
        int total = demoProducts.findByPool(DemoProduct.Pool.INBOUND).size();
        for (int i = 0; i < total; i++) {
            mvc.perform(post(NEXT_BARCODE)).andExpect(status().isOk());
        }

        mvc.perform(post(NEXT_BARCODE)).andExpect(status().isNoContent());
    }

    @Test
    void 리셋하면_처음부터_다시_준다() throws Exception {
        MvcResult first = mvc.perform(post(NEXT_BARCODE)).andReturn();
        String firstBarcode = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.barcode");

        mvc.perform(post(RESET)).andExpect(status().isOk());

        mvc.perform(post(NEXT_BARCODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barcode").value(firstBarcode));
    }

    @Test
    void 촬영_전에_다시_눌러도_같은_바코드를_주지_않는다() throws Exception {
        // 화면에서 버튼을 두 번 누르는 상황. 확정 여부로만 판단하면 같은 것이 또 나온다.
        MvcResult first = mvc.perform(post(NEXT_BARCODE)).andReturn();
        MvcResult second = mvc.perform(post(NEXT_BARCODE)).andReturn();

        String a = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.barcode");
        String b = com.jayway.jsonpath.JsonPath.read(
                second.getResponse().getContentAsString(), "$.barcode");
        assertThat(a).isNotEqualTo(b);
    }
}
