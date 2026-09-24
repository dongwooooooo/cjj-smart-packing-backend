package com.awesome.backend.inventory.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정합성 대조기. 상품별 원장 전체 합과 (스냅샷 + 미집계 차분)을 대조한다. 스냅샷은 원장에서
 * 유도한 값이라 어긋남의 원인은 코드 버그나 직접 SQL 뿐이고, 원장을 진실로 두고 스냅샷을
 * 다시 만드는 것이 안전하다 (D-L6). 원장은 고치지 않는다.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    private static final String MISMATCHES = """
            SELECT b.product_id,
                   b.qty + COALESCE(SUM(t.qty_delta) FILTER (WHERE t.id > b.last_tx_id), 0) AS derived,
                   COALESCE(SUM(t.qty_delta), 0) AS ledger_total
              FROM stock_balance b LEFT JOIN inventory_tx t ON t.product_id = b.product_id
             GROUP BY b.product_id, b.qty, b.last_tx_id
            HAVING b.qty + COALESCE(SUM(t.qty_delta) FILTER (WHERE t.id > b.last_tx_id), 0)
                <> COALESCE(SUM(t.qty_delta), 0)
            """;

    private static final String REBUILD = """
            UPDATE stock_balance
               SET qty = (SELECT COALESCE(SUM(qty_delta), 0) FROM inventory_tx WHERE product_id = ?),
                   last_tx_id = (SELECT COALESCE(MAX(id), 0) FROM inventory_tx WHERE product_id = ?),
                   computed_at = now()
             WHERE product_id = ?
            """;

    private static final String NEGATIVE = "SELECT COUNT(*) FROM v_stock_on_hand WHERE on_hand_qty < 0";

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong mismatch = new AtomicLong();
    private final AtomicLong negative = new AtomicLong();

    public StockReconciler(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("inventory.reconcile.mismatch", mismatch, AtomicLong::get)
                .description("마지막 대조에서 원장과 어긋나 복구한 상품 수").register(registry);
        Gauge.builder("inventory.balance.negative", negative, AtomicLong::get)
                .description("실재고가 음수인 상품 수 — 실물과 장부가 어긋났다는 신호").register(registry);
    }

    @Scheduled(fixedDelayString = "${inventory.reconciler.interval-ms:60000}")
    public void reconcile() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("stock reconcile failed", e);
        }
    }

    /** 한 번 대조한다. 복구한 상품 수를 돌려준다. */
    @Transactional
    public int reconcileOnce() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(MISMATCHES);
        for (Map<String, Object> row : rows) {
            long productId = ((Number) row.get("product_id")).longValue();
            log.warn("stock balance mismatch productId={} derived={} ledgerTotal={} -> rebuilt from ledger",
                    productId, row.get("derived"), row.get("ledger_total"));
            jdbcTemplate.update(REBUILD, productId, productId, productId);
        }
        mismatch.set(rows.size());
        Long negatives = jdbcTemplate.queryForObject(NEGATIVE, Long.class);
        negative.set(negatives == null ? 0 : negatives);
        if (negatives != null && negatives > 0) {
            log.warn("negative on-hand products={}", negatives);
        }
        return rows.size();
    }
}
