package com.awesome.backend.inventory.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔고 스냅샷 집계기. last_tx_id 이후 원장 중 정착한 행만 상품별로 합쳐 stock_balance 에 더한다.
 *
 * <h2>정착 창 — 커밋 순서가 id 순서와 다른 행을 건너뛰지 않기</h2>
 * 원장 id 는 INSERT 때 받지만 행이 보이는 것은 커밋 뒤다. 포장 완료는 원장 행을 넣은 뒤 박스 행 락을
 * 기다리므로, id 2 를 받은 트랜잭션이 id 3 보다 늦게 커밋하는 일이 흔하다. 커서를 보이는 행의
 * {@code MAX(id)} 로 옮기면 id 2 는 {@code last_tx_id} 아래에 묻혀 스냅샷에도, 조회의 미집계 합
 * ({@code id > last_tx_id})에도 영원히 들어가지 않는다.
 *
 * <p>그래서 {@code created_at}(DB 가 INSERT 순간 기록하는 clock_timestamp(), V22)이 정착 창
 * ({@code inventory.collector.settle-seconds}, 기본 60초)보다 오래된 행만 접고, 창 안의 행(young)이
 * 하나라도 보이면 그 행 바로 앞에서 멈춘다. 가정: 원장을 쓰는 트랜잭션은 행을 넣은 뒤 창 안에 커밋하거나
 * 롤백한다(포장 완료 약 1초). 근거 — 접는 마지막 행 X 의 created_at 이 창보다 오래됐다면, X 보다 작은
 * id 를 받은 트랜잭션은 X 보다 먼저 nextval 을 받았으므로(IDENTITY 시퀀스 CACHE 1 — 세션별 선할당
 * 없음) 행을 넣은 지 창보다 오래됐고, 가정에 따라
 * 이 문장의 스냅샷 시각(statement_timestamp() 이후) 전에 이미 끝났다. 따라서 {@code (last_tx_id, X]}
 * 구간은 스냅샷 안에서 완결돼 있다.
 *
 * <h2>동시 집계 — 이중 반영이 없는 이유</h2>
 * 서브쿼리와 FROM 의 파생 테이블 {@code s} 는 모두 문장 시작 시점 스냅샷을 읽는다. 두 인스턴스가 같은
 * 상품을 동시에 접으면 늦은 쪽(C2)은 행 락을 기다렸다가 PostgreSQL READ COMMITTED 의 재검사
 * (EvalPlanQual)로 먼저 커밋한 쪽(C1)이 남긴 최신 행 {@code b} 를 받는다. 이때 {@code b.last_tx_id}·
 * {@code b.qty} 는 새 값(C1 의 N1)이고, {@code s.new_last} 는 C2 가 처음 계산한 값(N2) 그대로다
 * (파생 테이블·조인 행은 재검사 때 다시 계산하지 않는다).
 * <ul>
 *   <li>N1 ≥ N2 — WHERE {@code b.last_tx_id < s.new_last} 가 거짓이 되어 C2 는 건너뛴다.</li>
 *   <li>N1 &lt; N2 — SET 서브쿼리가 {@code (N1, N2]} 만 더한다. C1 이 이미 더한 구간 {@code (.., N1]}
 *       과 겹치지 않고, 이 구간은 C2 스냅샷에서 위 정착 논증으로 완결돼 있으므로 잔여분이 정확하다.</li>
 * </ul>
 * 어느 경우든 이중 반영도 누락도 없다. 대조기의 REBUILD 와 겹쳐도 같은 논증이 성립한다.
 *
 * <p>LAG 지표는 창 안의 행도 미집계로 센다 — 정상 상태에서도 최근 창만큼의 행이 남는다.
 */
@Component
public class StockBalanceCollector {

    private static final Logger log = LoggerFactory.getLogger(StockBalanceCollector.class);

    private static final String INSERT_MISSING = """
            INSERT INTO stock_balance (product_id, qty, last_tx_id)
            SELECT p.id, 0, 0 FROM product p
            WHERE NOT EXISTS (SELECT 1 FROM stock_balance b WHERE b.product_id = p.id)
            """;

    /**
     * 상품별 정착 커서 new_last 를 구해 {@code (last_tx_id, new_last]} 를 접는다. 서브쿼리는 모두 문장
     * 시작 시점 스냅샷을 읽는다. 기준 시각은 문장 안에서 고정인 statement_timestamp() — 스냅샷을 잡기
     * 전이라 정착 판단이 보수적이 된다. 파라미터: 정착 창(초) 두 번.
     */
    static final String ADVANCE = """
            UPDATE stock_balance b
               SET qty = b.qty + (SELECT COALESCE(SUM(t.qty_delta), 0) FROM inventory_tx t
                                   WHERE t.product_id = b.product_id
                                     AND t.id > b.last_tx_id AND t.id <= s.new_last),
                   last_tx_id = s.new_last,
                   computed_at = now()
              FROM (
                SELECT b2.product_id,
                       (SELECT MAX(t.id) FROM inventory_tx t
                         WHERE t.product_id = b2.product_id AND t.id > b2.last_tx_id
                           AND t.created_at < statement_timestamp() - make_interval(secs => ?::float8)
                           AND t.id < COALESCE((SELECT MIN(y.id) FROM inventory_tx y
                                                 WHERE y.product_id = b2.product_id AND y.id > b2.last_tx_id
                                                   AND y.created_at >= statement_timestamp()
                                                                       - make_interval(secs => ?::float8)),
                                               9223372036854775807)) AS new_last
                  FROM stock_balance b2
              ) s
             WHERE b.product_id = s.product_id AND s.new_last IS NOT NULL AND b.last_tx_id < s.new_last
            """;

    private static final String LAG = """
            SELECT COUNT(*) AS rows,
                   COALESCE(EXTRACT(EPOCH FROM (statement_timestamp()::timestamp - MIN(t.created_at))), 0)::bigint AS oldest_seconds
              FROM inventory_tx t JOIN stock_balance b ON b.product_id = t.product_id
             WHERE t.id > b.last_tx_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final long settleSeconds;
    private final AtomicLong lagRows = new AtomicLong();
    private final AtomicLong lagSeconds = new AtomicLong();

    public StockBalanceCollector(JdbcTemplate jdbcTemplate, InventoryProperties properties, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.settleSeconds = properties.collector().settleSeconds();
        Gauge.builder("inventory.collector.lag_rows", lagRows, AtomicLong::get)
                .description("아직 스냅샷에 더해지지 않은 원장 행 수 (정착 창 안의 행 포함)").register(registry);
        Gauge.builder("inventory.collector.lag_seconds", lagSeconds, AtomicLong::get)
                .description("가장 오래된 미집계 원장 행의 나이(초)").register(registry);
    }

    /**
     * 스케줄 경로는 {@code this.collectOnce()} 자기 호출이라 {@code @Transactional} 프록시를 거치지 않는다.
     * 그래서 각 JdbcTemplate 문장이 각자 자동 커밋된다 — 문장 하나하나가 단독으로 정확해 문제없다.
     * 트랜잭션 경계는 프록시로 부르는 테스트·호출자에게만 의미가 있다.
     */
    @Scheduled(initialDelayString = "${inventory.collector.interval-ms:5000}",
            fixedDelayString = "${inventory.collector.interval-ms:5000}")
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
        int updated = jdbcTemplate.update(ADVANCE, settleSeconds, settleSeconds);
        Map<String, Object> lag = jdbcTemplate.queryForMap(LAG);
        lagRows.set(((Number) lag.get("rows")).longValue());
        lagSeconds.set(((Number) lag.get("oldest_seconds")).longValue());
        return updated;
    }
}
