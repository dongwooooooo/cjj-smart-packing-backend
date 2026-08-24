package com.awesome.backend.inventory.repository;

import com.awesome.backend.inventory.entity.InventoryTx;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTxRepository extends JpaRepository<InventoryTx, Long> {

    List<InventoryTx> findByProductIdOrderByIdAsc(Long productId);
}
