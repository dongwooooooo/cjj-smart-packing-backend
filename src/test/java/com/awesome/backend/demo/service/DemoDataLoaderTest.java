package com.awesome.backend.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.awesome.backend.demo.entity.DemoProduct;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 시연 데이터 파일 로더 (명세 §2). 파일이 잘못됐을 때 무엇이 왜 잘못됐는지
 * 바로 알 수 있어야 한다 — 시연 직전에 고쳐야 하는 파일이라서다.
 */
class DemoDataLoaderTest {

    @TempDir
    Path dataDir;

    private final DemoDataLoader loader = new DemoDataLoader();

    private static final String ONE_PRODUCT = """
            [
              { "gtin": "8801234500011", "name": "델가 오렌지주스 1L",
                "mediumCategoryCode": "C1010", "pool": "OUTBOUND",
                "dims": { "widthCm": 7.0, "lengthCm": 7.0, "heightCm": 23.0 },
                "weightKg": 1.080,
                "flags": { "refrigerate": true, "fragile": true, "irregular": false },
                "stockQty": 6,
                "images": [] }
            ]
            """;

    private Path write(String fileName, String content) throws IOException {
        Path file = dataDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void 상품_파일을_읽어_항목으로_돌려준다() throws IOException {
        write("products.json", ONE_PRODUCT);

        List<DemoProductSpec> products = loader.loadProducts(dataDir);

        assertThat(products).hasSize(1);
        DemoProductSpec product = products.getFirst();
        assertThat(product.gtin()).isEqualTo("8801234500011");
        assertThat(product.name()).isEqualTo("델가 오렌지주스 1L");
        assertThat(product.pool()).isEqualTo(DemoProduct.Pool.OUTBOUND);
        assertThat(product.widthCm()).isEqualByComparingTo(BigDecimal.valueOf(7.0));
        assertThat(product.heightCm()).isEqualByComparingTo(BigDecimal.valueOf(23.0));
        assertThat(product.weightKg()).isEqualByComparingTo(BigDecimal.valueOf(1.080));
        assertThat(product.fragile()).isTrue();
        assertThat(product.irregular()).isFalse();
        assertThat(product.stockQty()).isEqualTo(6);
    }

    @Test
    void 가로가_세로보다_짧으면_두_변을_바꿔_읽는다() throws IOException {
        // 축 규약 D-18 — 긴 쪽이 width. 거부가 아니라 정렬이다 (서버가 저장 전에 맞춘다)
        write("products.json", """
                [
                  { "gtin": "8801234500042", "name": "허니버터칩 60g",
                    "mediumCategoryCode": "C2010", "pool": "OUTBOUND",
                    "dims": { "widthCm": 13.0, "lengthCm": 20.0, "heightCm": 5.0 },
                    "weightKg": 0.068,
                    "flags": { "refrigerate": false, "fragile": false, "irregular": false },
                    "stockQty": 50, "images": [] }
                ]
                """);

        DemoProductSpec product = loader.loadProducts(dataDir).getFirst();

        assertThat(product.widthCm()).isEqualByComparingTo(BigDecimal.valueOf(20.0));
        assertThat(product.lengthCm()).isEqualByComparingTo(BigDecimal.valueOf(13.0));
        assertThat(product.heightCm()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
    }

    @Test
    void 상품_파일이_없으면_경로를_알려준다() {
        assertThatThrownBy(() -> loader.loadProducts(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("products.json");
    }

    @Test
    void 필수_항목이_비면_몇_번째_상품의_무엇인지_알려준다() throws IOException {
        write("products.json", """
                [
                  { "gtin": "8801234500011", "name": "델가 오렌지주스 1L",
                    "mediumCategoryCode": "C1010", "pool": "OUTBOUND",
                    "weightKg": 1.080,
                    "flags": { "refrigerate": true, "fragile": true, "irregular": false },
                    "stockQty": 6, "images": [] }
                ]
                """);

        assertThatThrownBy(() -> loader.loadProducts(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("dims")
                .hasMessageContaining("8801234500011");
    }

    @Test
    void 모르는_풀_이름이면_쓸_수_있는_값을_알려준다() throws IOException {
        write("products.json", ONE_PRODUCT.replace("OUTBOUND", "PACKING"));

        assertThatThrownBy(() -> loader.loadProducts(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("PACKING")
                .hasMessageContaining("INBOUND");
    }

    @Test
    void 치수가_0_이하면_거부한다() throws IOException {
        write("products.json", ONE_PRODUCT.replace("\"heightCm\": 23.0", "\"heightCm\": 0"));

        assertThatThrownBy(() -> loader.loadProducts(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("heightCm");
    }

    @Test
    void 바코드가_겹치면_거부한다() throws IOException {
        write("products.json", "[" + ONE_PRODUCT.substring(1, ONE_PRODUCT.lastIndexOf(']')) + ","
                + ONE_PRODUCT.substring(1, ONE_PRODUCT.lastIndexOf(']')) + "]");

        assertThatThrownBy(() -> loader.loadProducts(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("8801234500011");
    }

    @Test
    void 배치_파일을_투입_순서대로_돌려준다() throws IOException {
        write("orders.json", """
                [
                  { "batchId": "DEMO-1",
                    "orders": [ { "receiptNo": "R-1", "regionCode": "SEOUL",
                                  "orderedAt": "2026-08-24T09:00:00",
                                  "items": [ { "gtin": "8801234500042", "qty": 2 } ] } ] },
                  { "batchId": "DEMO-2",
                    "orders": [ { "receiptNo": "R-2", "regionCode": "BUSAN",
                                  "orderedAt": "2026-08-24T09:05:00",
                                  "items": [ { "gtin": "8801234500066", "qty": 1 } ] } ] }
                ]
                """);

        List<String> batches = loader.loadOrderBatches(dataDir);

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).contains("DEMO-1").contains("R-1");
        assertThat(batches.get(1)).contains("DEMO-2").contains("R-2");
    }

    @Test
    void 배치에_주문이_하나도_없으면_거부한다() throws IOException {
        write("orders.json", """
                [ { "batchId": "DEMO-1", "orders": [] } ]
                """);

        assertThatThrownBy(() -> loader.loadOrderBatches(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("DEMO-1");
    }

    @Test
    void 배치_파일이_배열이_아니면_거부한다() throws IOException {
        write("orders.json", """
                { "batchId": "DEMO-1", "orders": [] }
                """);

        assertThatThrownBy(() -> loader.loadOrderBatches(dataDir))
                .isInstanceOf(DemoDataException.class)
                .hasMessageContaining("배열");
    }
}
