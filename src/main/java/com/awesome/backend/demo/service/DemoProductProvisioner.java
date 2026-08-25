package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
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
    private final ProductRepository productRepository;
    private final DemoProductRepository demoProductRepository;
    private final InventoryService inventoryService;

    public DemoProductProvisioner(JdbcTemplate jdbcTemplate, ProductRepository productRepository,
                                  DemoProductRepository demoProductRepository,
                                  InventoryService inventoryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.productRepository = productRepository;
        this.demoProductRepository = demoProductRepository;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public void provision(List<DemoProductSpec> specs) {
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
     * 재고는 0으로 되돌린다 — 리셋이 원장을 비웠으므로 캐시도 같이 0에서 시작해야
     * 원장 합계와 어긋나지 않는다. 목표 수량은 뒤에서 원장을 거쳐 채운다.
     */
    private void upsertProduct(DemoProductSpec spec) {
        boolean outbound = spec.pool() == DemoProduct.Pool.OUTBOUND;
        jdbcTemplate.update("""
                insert into product (gtin, name, medium_category_code, image_url, source,
                                     width_cm, length_cm, height_cm, weight_kg,
                                     dim_status, dim_method,
                                     is_refrigerate, is_fragile, is_irregular, stock_qty)
                values (?, ?, ?, ?, 'MASTER', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
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
                       stock_qty = 0,
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

    /** 목표 재고와의 차이만큼 보정 기록을 남긴다. 입고 풀은 목표가 0이라 남은 재고를 털어낸다. */
    private void alignStock(DemoProductSpec spec) {
        int current = productRepository.findByGtin(spec.gtin()).orElseThrow().stockQty();
        int delta = spec.stockQty() - current;
        if (delta != 0) {
            inventoryService.adjust(spec.gtin(), delta);
        }
    }
}
