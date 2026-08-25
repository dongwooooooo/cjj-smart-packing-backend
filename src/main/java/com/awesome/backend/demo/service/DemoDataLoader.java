package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 시연 데이터 파일을 읽어 검증한다 (명세 §2).
 *
 * <p>파일은 데이터셋 머신에서 추출해 레포에 넣는 것이라 서버가 손댈 수 없다.
 * 잘못된 채로 들어오면 런을 시작하는 시점에 터지므로, 여기서 미리 잡아
 * 무엇을 고쳐야 하는지 메시지로 알려준다.
 */
@Service
public class DemoDataLoader {

    private static final String PRODUCTS_FILE = "products.json";
    private static final String ORDERS_FILE = "orders.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DemoProductSpec> loadProducts(Path dataDir) {
        JsonNode root = readArray(dataDir.resolve(PRODUCTS_FILE), PRODUCTS_FILE);
        List<DemoProductSpec> products = new ArrayList<>();
        Set<String> seenGtins = new LinkedHashSet<>();
        for (int i = 0; i < root.size(); i++) {
            DemoProductSpec product = toProduct(root.get(i), i);
            if (!seenGtins.add(product.gtin())) {
                throw new DemoDataException(PRODUCTS_FILE + ": 같은 바코드가 두 번 들어 있습니다 — "
                        + product.gtin());
            }
            products.add(product);
        }
        if (products.isEmpty()) {
            throw new DemoDataException(PRODUCTS_FILE + ": 상품이 하나도 없습니다.");
        }
        return List.copyOf(products);
    }

    /** 배치를 접수 요청 본문 그대로 돌려준다 — 투입할 때 그대로 import에 넘긴다. */
    public List<String> loadOrderBatches(Path dataDir) {
        JsonNode root = readArray(dataDir.resolve(ORDERS_FILE), ORDERS_FILE);
        List<String> batches = new ArrayList<>();
        Set<String> seenReceiptNos = new LinkedHashSet<>();
        for (int i = 0; i < root.size(); i++) {
            JsonNode batch = root.get(i);
            String batchId = text(batch, "batchId", ORDERS_FILE, (i + 1) + "번째 배치");
            JsonNode orders = batch.path("orders");
            if (!orders.isArray() || orders.isEmpty()) {
                throw new DemoDataException(ORDERS_FILE + ": 배치 " + batchId + "에 주문이 없습니다.");
            }
            for (JsonNode order : orders) {
                String receiptNo = text(order, "receiptNo", ORDERS_FILE, "배치 " + batchId);
                // 런 시작 때 주문번호 앞에 런 아이디만 붙이므로, 파일 안에서 이미 겹치면
                // 런 안에서도 겹친다 — 배치가 달라도 마찬가지다
                if (!seenReceiptNos.add(receiptNo)) {
                    throw new DemoDataException(ORDERS_FILE
                            + ": 같은 주문번호가 두 번 들어 있습니다 — " + receiptNo
                            + " (배치 " + batchId + ")");
                }
            }
            batches.add(batch.toString());
        }
        if (batches.isEmpty()) {
            throw new DemoDataException(ORDERS_FILE + ": 배치가 하나도 없습니다.");
        }
        return List.copyOf(batches);
    }

    private JsonNode readArray(Path file, String fileName) {
        if (!Files.exists(file)) {
            throw new DemoDataException("시연 데이터 파일이 없습니다 — " + file.toAbsolutePath()
                    + " (" + fileName + ")");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new DemoDataException(fileName + ": 읽을 수 없는 JSON입니다 — " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new DemoDataException(fileName + ": 최상위가 배열이어야 합니다.");
        }
        return root;
    }

    private DemoProductSpec toProduct(JsonNode node, int index) {
        String gtin = text(node, "gtin", PRODUCTS_FILE, where(index, null));
        String where = where(index, gtin);
        JsonNode dims = node.path("dims");
        if (dims.isMissingNode() || !dims.isObject()) {
            throw new DemoDataException(PRODUCTS_FILE + ": " + where + " dims가 없습니다.");
        }
        BigDecimal width = positiveDecimal(dims, "widthCm", where);
        BigDecimal length = positiveDecimal(dims, "lengthCm", where);
        BigDecimal height = positiveDecimal(dims, "heightCm", where);

        JsonNode flags = node.path("flags");
        if (!flags.isObject()) {
            throw new DemoDataException(PRODUCTS_FILE + ": " + where + " flags가 없습니다.");
        }
        int stockQty = node.path("stockQty").asInt(-1);
        if (stockQty < 0) {
            throw new DemoDataException(PRODUCTS_FILE + ": " + where
                    + " stockQty가 없거나 음수입니다.");
        }
        return new DemoProductSpec(gtin,
                text(node, "name", PRODUCTS_FILE, where),
                text(node, "mediumCategoryCode", PRODUCTS_FILE, where),
                pool(node, where),
                // D-18 축 규약 — 긴 쪽이 width. 거부하지 않고 서버가 맞춰 읽는다
                width.max(length),
                width.min(length),
                height,
                positiveDecimal(node, "weightKg", where),
                flags.path("refrigerate").asBoolean(false),
                flags.path("fragile").asBoolean(false),
                flags.path("irregular").asBoolean(false),
                stockQty,
                images(node));
    }

    private DemoProduct.Pool pool(JsonNode node, String where) {
        String value = text(node, "pool", PRODUCTS_FILE, where);
        try {
            return DemoProduct.Pool.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new DemoDataException(PRODUCTS_FILE + ": " + where + " 모르는 pool 값입니다 — "
                    + value + " (쓸 수 있는 값: " + Arrays.toString(DemoProduct.Pool.values()) + ")", e);
        }
    }

    private List<String> images(JsonNode node) {
        JsonNode images = node.path("images");
        if (!images.isArray()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        images.forEach(image -> paths.add(image.asText()));
        return List.copyOf(paths);
    }

    private String text(JsonNode node, String field, String fileName, String where) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new DemoDataException(fileName + ": " + where + " " + field + "가 비어 있습니다.");
        }
        return value;
    }

    private BigDecimal positiveDecimal(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || value.decimalValue().signum() <= 0) {
            throw new DemoDataException(PRODUCTS_FILE + ": " + where + " " + field
                    + "가 없거나 0 이하입니다.");
        }
        return value.decimalValue();
    }

    private String where(int index, String gtin) {
        String position = (index + 1) + "번째 상품";
        return gtin == null || gtin.isBlank() ? position : position + "(" + gtin + ")";
    }
}
