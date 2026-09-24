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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정합성 대조기. 상품별 원장 전체 합과 (스냅샷 + 미집계 차분)을 대조한다. 스냅샷은 원장에서
 * 유도한 값이라 어긋남의 원인은 코드 버그나 직접 SQL 뿐이고, 원장을 진실로 두고 스냅샷을
 * 다시 만드는 것이 안전하다 (D-L6). 원장은 고치지 않는다. 상품별 복구는 각자 독립된
 * 트랜잭션으로 실행해, 한 상품의 복구 실패가 다른 상품의 복구를 막지 않는다 (spec 7절).
 *
 * <p>MISMATCHES 는 같은 스냅샷 안에서 {@code qty + Σ(id > last_tx_id)} 와 원장 전체 합을 비교하므로
 * 정착 창과 무관하게 성립한다. REBUILD 는 커서를 집계기와 같은 정착 규칙으로 정한다. 복구는
 * {@code transactionTemplate} 으로 감싸므로 {@code reconcile()} 의 자기 호출과 무관하게 상품마다
 * 트랜잭션 경계가 있다.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    static final String MISMATCHES = """
            SELECT b.product_id,
                   b.qty + COALESCE(SUM(t.qty_delta) FILTER (WHERE t.id > b.last_tx_id), 0) AS derived,
                   COALESCE(SUM(t.qty_delta), 0) AS ledger_total
              FROM stock_balance b LEFT JOIN inventory_tx t ON t.product_id = b.product_id
             GROUP BY b.product_id, b.qty, b.last_tx_id
            HAVING b.qty + COALESCE(SUM(t.qty_delta) FILTER (WHERE t.id > b.last_tx_id), 0)
                <> COALESCE(SUM(t.qty_delta), 0)
            """;

    /**
     * 원장 기준으로 스냅샷을 다시 만든다. 커서는 집계기와 같은 정착 규칙으로 정한다 — 원장 전체의
     * {@code MAX(id)} 로 두면 아직 커밋되지 않은 더 작은 id 행을 영원히 건너뛸 수 있다
     * (StockBalanceCollector 클래스 주석). 정착한 행이 없으면 {@code (0, 0)}.
     * 파라미터: 정착 창(초), 상품 id, 정착 창(초), 상품 id, 상품 id.
     */
    static final String REBUILD = """
            UPDATE stock_balance b
               SET qty = (SELECT COALESCE(SUM(t.qty_delta), 0) FROM inventory_tx t
                           WHERE t.product_id = b.product_id AND t.id <= s.new_last),
                   last_tx_id = s.new_last,
                   computed_at = now()
              FROM (
                SELECT COALESCE((SELECT MAX(t.id) FROM inventory_tx t
                                  WHERE t.product_id = ?
                                    AND t.created_at < statement_timestamp() - make_interval(secs => ?::float8)
                                    AND t.id < COALESCE((SELECT MIN(y.id) FROM inventory_tx y
                                                          WHERE y.product_id = ?
                                                            AND y.created_at >= statement_timestamp()
                                                                                - make_interval(secs => ?::float8)),
                                                        9223372036854775807)), 0) AS new_last
              ) s
             WHERE b.product_id = ?
            """;

    static final String NEGATIVE = "SELECT COUNT(*) FROM v_stock_on_hand WHERE on_hand_qty < 0";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final long settleSeconds;
    private final AtomicLong mismatch = new AtomicLong();
    private final AtomicLong negative = new AtomicLong();

    public StockReconciler(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                           InventoryProperties properties, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.settleSeconds = properties.collector().settleSeconds();
        Gauge.builder("inventory.reconcile.mismatch", mismatch, AtomicLong::get)
                .description("마지막 대조에서 원장과 어긋난 상품 수").register(registry);
        Gauge.builder("inventory.balance.negative", negative, AtomicLong::get)
                .description("실재고가 음수인 상품 수 — 실물과 장부가 어긋났다는 신호").register(registry);
    }

    @Scheduled(initialDelayString = "${inventory.reconciler.interval-ms:60000}",
            fixedDelayString = "${inventory.reconciler.interval-ms:60000}")
    public void reconcile() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("stock reconcile failed", e);
        }
    }

    /** 한 번 대조한다. 성공적으로 복구한 상품 수를 돌려준다. */
    public int reconcileOnce() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(MISMATCHES);
        int fixed = 0;
        for (Map<String, Object> row : rows) {
            long productId = ((Number) row.get("product_id")).longValue();
            try {
                transactionTemplate.executeWithoutResult(
                        status -> jdbcTemplate.update(REBUILD, productId, settleSeconds, productId, settleSeconds, productId));
                fixed++;
                log.warn("stock balance mismatch productId={} derived={} ledgerTotal={} -> rebuilt from ledger",
                        productId, row.get("derived"), row.get("ledger_total"));
            } catch (RuntimeException e) {
                log.warn("stock balance rebuild failed productId={}", productId, e);
            }
        }
        mismatch.set(rows.size());
        Long negatives = jdbcTemplate.queryForObject(NEGATIVE, Long.class);
        negative.set(negatives == null ? 0 : negatives);
        if (negatives != null && negatives > 0) {
            log.warn("negative on-hand products={}", negatives);
        }
        return fixed;
    }
}
