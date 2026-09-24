package com.awesome.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StockReconcilerTest {

    @Test
    void 한_상품의_복구_실패가_다른_상품_복구를_막지_않는다() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        StockReconciler reconciler = new StockReconciler(jdbcTemplate, transactionTemplate, registry);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("product_id", 1L);
        row1.put("derived", 10);
        row1.put("ledger_total", 20);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("product_id", 2L);
        row2.put("derived", 5);
        row2.put("ledger_total", 7);
        when(jdbcTemplate.queryForList(StockReconciler.MISMATCHES)).thenReturn(List.of(row1, row2));

        doThrow(new DataAccessResourceFailureException("boom"))
                .when(jdbcTemplate).update(eq(StockReconciler.REBUILD), eq(1L), eq(1L), eq(1L));
        when(jdbcTemplate.update(eq(StockReconciler.REBUILD), eq(2L), eq(2L), eq(2L))).thenReturn(1);

        when(jdbcTemplate.queryForObject(StockReconciler.NEGATIVE, Long.class)).thenReturn(0L);

        int fixed = reconciler.reconcileOnce();

        assertThat(fixed).isEqualTo(1);
        verify(jdbcTemplate).update(eq(StockReconciler.REBUILD), eq(2L), eq(2L), eq(2L));
        assertThat(registry.get("inventory.reconcile.mismatch").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("inventory.balance.negative").gauge().value()).isEqualTo(0.0);
    }
}
