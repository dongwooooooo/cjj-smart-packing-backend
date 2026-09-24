package com.awesome.backend.inventory.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔고 스냅샷 집계기. last_tx_id 이후 원장을 상품별로 합쳐 stock_balance 에 더한다.
 *
 * <p>ADVANCE 의 SET·WHERE 서브쿼리는 모두 갱신 대상 행 {@code b} 의 {@code last_tx_id} 를
 * 상관(correlated)으로 참조한다. PostgreSQL 은 READ COMMITTED 에서 UPDATE 가 행 락을 얻은
 * 뒤(EvalPlanQual) 그 행의 최신 버전을 기준으로 SET·WHERE 절을 다시 평가한다 — 두 인스턴스가
 * 같은 상품을 동시에 집계해도, 먼저 커밋한 쪽이 last_tx_id 를 전진시키면 나중 쪽은 락을 얻은
 * 시점에 이미 전진한 last_tx_id 로 서브쿼리를 다시 돌려 이중 반영되지 않는다. 이전의
 * {@code WITH d AS (...) UPDATE ... FROM d} 형태는 CTE 를 UPDATE 시작 시점에 한 번만 계산해
 * 두 번째 인스턴스가 첫 번째가 이미 더한 구간을 다시 더할 수 있었다.
 */
@Component
public class StockBalanceCollector {

    private static final Logger log = LoggerFactory.getLogger(StockBalanceCollector.class);

    private static final String INSERT_MISSING = """
            INSERT INTO stock_balance (product_id, qty, last_tx_id)
            SELECT p.id, 0, 0 FROM product p
            WHERE NOT EXISTS (SELECT 1 FROM stock_balance b WHERE b.product_id = p.id)
            """;

    private static final String ADVANCE = """
            UPDATE stock_balance b
               SET qty = b.qty + (SELECT COALESCE(SUM(t.qty_delta), 0) FROM inventory_tx t
                                   WHERE t.product_id = b.product_id AND t.id > b.last_tx_id),
                   last_tx_id = (SELECT MAX(t.id) FROM inventory_tx t
                                  WHERE t.product_id = b.product_id AND t.id > b.last_tx_id),
                   computed_at = now()
             WHERE EXISTS (SELECT 1 FROM inventory_tx t
                            WHERE t.product_id = b.product_id AND t.id > b.last_tx_id)
            """;

    private static final String LAG = """
            SELECT COUNT(*) AS rows, MIN(t.created_at) AS oldest
              FROM inventory_tx t JOIN stock_balance b ON b.product_id = t.product_id
             WHERE t.id > b.last_tx_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong lagRows = new AtomicLong();
    private final AtomicLong lagSeconds = new AtomicLong();

    public StockBalanceCollector(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("inventory.collector.lag_rows", lagRows, AtomicLong::get)
                .description("아직 스냅샷에 더해지지 않은 원장 행 수").register(registry);
        Gauge.builder("inventory.collector.lag_seconds", lagSeconds, AtomicLong::get)
                .description("가장 오래된 미집계 원장 행의 나이(초)").register(registry);
    }

    @Scheduled(fixedDelayString = "${inventory.collector.interval-ms:5000}")
    public void collect() {
        try {
            collectOnce();
        } catch (RuntimeException e) {
            log.warn("stock balance collect failed", e);
        }
    }

    /** 한 번 집계한다. 갱신한 상품 수를 돌려준다. */
    @Transactional
    public int collectOnce() {
        jdbcTemplate.update(INSERT_MISSING);
        int updated = jdbcTemplate.update(ADVANCE);
        Map<String, Object> lag = jdbcTemplate.queryForMap(LAG);
        lagRows.set(((Number) lag.get("rows")).longValue());
        Object oldest = lag.get("oldest");
        lagSeconds.set(oldest == null ? 0
                : ChronoUnit.SECONDS.between(((java.sql.Timestamp) oldest).toLocalDateTime(), LocalDateTime.now()));
        return updated;
    }
}
