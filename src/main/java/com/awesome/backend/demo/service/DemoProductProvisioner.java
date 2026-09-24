package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inventory.service.AvailableStockQuery;
import com.awesome.backend.inventory.service.InventoryService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * products.json을 상품 마스터·상품·데모 상품에 반영한다 (명세 §4-3).
 *
 * <p>같은 상품이 이미 있으면 덮어쓴다. 런을 몇 번 돌려도 상품 행은 늘지 않는다.
 *
 * <p>상품 행은 SQL로 직접 upsert한다. 입고 풀은 치수를 비우고 확정 표시를 되돌려야 하는데,
 * P1의 Product에는 확정을 되돌리는 전이가 없다 — 시연 리셋을 위해 P1 엔티티에 전이를
 * 새로 뚫는 대신, 시연 쪽에서 행을 직접 맞춘다.
 *
 * <p>재고만은 예외로 InventoryService를 거친다. 재고 증감은 원장 기록과 함께 한 창구로만
 * 한다는 규칙(D-09)이 시연 리셋에도 그대로 적용된다.
 */
@Component
public class DemoProductProvisioner {

    private final JdbcTemplate jdbcTemplate;
    private final DemoProductRepository demoProductRepository;
    private final InventoryService inventoryService;
    private final AvailableStockQuery stockQuery;

    public DemoProductProvisioner(JdbcTemplate jdbcTemplate,
                                  DemoProductRepository demoProductRepository,
                                  InventoryService inventoryService,
                                  AvailableStockQuery stockQuery) {
        this.jdbcTemplate = jdbcTemplate;
        this.demoProductRepository = demoProductRepository;
        this.inventoryService = inventoryService;
        this.stockQuery = stockQuery;
    }

    @Transactional
    public void provision(List<DemoProductSpec> specs) {
        pruneDropped(specs);
        for (DemoProductSpec spec : specs) {
            upsertMaster(spec);
            upsertProduct(spec);
            upsertDemoProduct(spec);
        }
        // 상품 행이 다 자리잡은 뒤에 재고를 맞춘다 — 새로 만든 상품도 조회되게 하려는 순서다
        specs.forEach(this::alignStock);
    }

    private void upsertMaster(DemoProductSpec spec) {
        jdbcTemplate.update("""
                insert into korean_net_master
                       (gtin, product_name, medium_category_code, image_url, batch_id, imported_at)
                values (?, ?, ?, ?, 'DEMO_RUN', now())
                on conflict (gtin) do update
                   set product_name = excluded.product_name,
                       medium_category_code = excluded.medium_category_code
                """, spec.gtin(), spec.name(), spec.mediumCategoryCode(),
                Product.PLACEHOLDER_IMAGE_URL);
    }

    /**
     * 입고 풀은 치수를 비우고 미확정으로, 출고 풀은 파일의 치수를 확정으로 넣는다.
     * 재고는 목표 수량만큼 뒤에서 원장을 거쳐 채운다(alignStock).
     */
    private void upsertProduct(DemoProductSpec spec) {
        boolean outbound = spec.pool() == DemoProduct.Pool.OUTBOUND;
        jdbcTemplate.update("""
                insert into product (gtin, name, medium_category_code, image_url, source,
                                     width_cm, length_cm, height_cm, weight_kg,
                                     dim_status, dim_method,
                                     is_refrigerate, is_fragile, is_irregular)
                values (?, ?, ?, ?, 'MASTER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (gtin) do update
                   set name = excluded.name,
                       medium_category_code = excluded.medium_category_code,
                       width_cm = excluded.width_cm,
                       length_cm = excluded.length_cm,
                       height_cm = excluded.height_cm,
                       weight_kg = excluded.weight_kg,
                       dim_status = excluded.dim_status,
                       dim_method = excluded.dim_method,
                       is_refrigerate = excluded.is_refrigerate,
                       is_fragile = excluded.is_fragile,
                       is_irregular = excluded.is_irregular,
                       updated_at = now()
                """,
                spec.gtin(), spec.name(), spec.mediumCategoryCode(), Product.PLACEHOLDER_IMAGE_URL,
                outbound ? spec.widthCm() : null,
                outbound ? spec.lengthCm() : null,
                outbound ? spec.heightCm() : null,
                spec.weightKg(),
                outbound ? Product.DIM_STATUS_CONFIRMED : Product.DIM_STATUS_NONE,
                outbound ? Product.DIM_METHOD_MANUAL : null,
                spec.refrigerate(), spec.fragile(), spec.irregular());
    }

    private void upsertDemoProduct(DemoProductSpec spec) {
        DemoProduct demoProduct = demoProductRepository.findById(spec.gtin()).orElse(null);
        if (demoProduct == null) {
            demoProductRepository.save(new DemoProduct(spec.gtin(), spec.pool(),
                    spec.widthCm(), spec.lengthCm(), spec.heightCm(), spec.imageDir()));
            return;
        }
        demoProduct.refresh(spec.pool(), spec.widthCm(), spec.lengthCm(), spec.heightCm(),
                spec.imageDir());
    }

    /**
     * 지난 런에 있었지만 이번 products.json 에서 빠진 상품을 데모에서 뺀다.
     *
     * <p>리셋은 몇 번을 눌러도 파일과 같은 상태를 만든다는 약속이다 (명세 §4). 그런데 상품은
     * upsert 만 하고 있어서, 목록을 줄이면 예전 상품이 데모 풀에 그대로 남았다. 남은 상품은
     * 화면의 상품 수를 부풀리고, 사진이 이미 지워졌으면 촬영이 {@code NO_IMAGES} 로 실패한다.
     *
     * <p>재고도 0 으로 되돌린다 — 실재고가 0 이 아니면 원장에 보정 기록을 남겨 턴다.
     * 상품 행 자체는 지우지 않는다. 마스터에 있던 상품이 사라질 이유는 없고,
     * 데모에서 빠졌을 뿐이다.
     */
    private void pruneDropped(List<DemoProductSpec> specs) {
        List<String> keep = specs.stream().map(DemoProductSpec::gtin).toList();
        if (keep.isEmpty()) {
            zeroStock(jdbcTemplate.queryForList("select gtin from demo_product", String.class));
            jdbcTemplate.update("delete from demo_product");
            return;
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(keep.size(), "?"));
        Object[] args = keep.toArray();
        zeroStock(jdbcTemplate.queryForList(
                "select gtin from demo_product where gtin not in (%s)".formatted(placeholders),
                String.class, args));
        jdbcTemplate.update(
                "delete from demo_product where gtin not in (%s)".formatted(placeholders), args);
    }

    private void zeroStock(List<String> gtins) {
        for (String gtin : gtins) {
            int onHand = stockQuery.onHandQty(gtin);
            if (onHand != 0) {
                inventoryService.adjustInternal(gtin, -onHand, "demo provision");
            }
        }
    }

    private void alignStock(DemoProductSpec spec) {
        int delta = spec.stockQty() - stockQuery.onHandQty(spec.gtin());
        if (delta != 0) {
            inventoryService.adjustInternal(spec.gtin(), delta, "demo provision");
        }
    }
}
