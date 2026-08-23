package com.awesome.backend.inventory.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTxRepository extends JpaRepository<InventoryTx, Long> {

    List<InventoryTx> findByProductIdOrderByIdAsc(Long productId);
}
