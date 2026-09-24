package com.awesome.backend.inventory.repository;

import com.awesome.backend.inventory.entity.StockBalance;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockBalanceRepository extends JpaRepository<StockBalance, Long> {

    Optional<StockBalance> findByProductId(Long productId);

    /**
     * 실재고 = 스냅샷 qty + (last_tx_id 이후 원장 합). 스냅샷 행이 없으면 qty 0, last_tx_id 0 으로
     * 계산해 원장 전체 합이 된다. 집계 주기와 무관하게 항상 정확하다 (D-L2).
     */
    @Query(value = """
            SELECT COALESCE((SELECT b.qty FROM stock_balance b WHERE b.product_id = :productId), 0)
                 + COALESCE((SELECT SUM(t.qty_delta) FROM inventory_tx t
                             WHERE t.product_id = :productId
                               AND t.id > COALESCE((SELECT b2.last_tx_id FROM stock_balance b2
                                                    WHERE b2.product_id = :productId), 0)), 0)
            """, nativeQuery = true)
    int onHandQty(@Param("productId") Long productId);
}
