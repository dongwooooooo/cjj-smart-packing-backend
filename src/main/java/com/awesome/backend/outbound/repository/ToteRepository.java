package com.awesome.backend.outbound.repository;

import com.awesome.backend.outbound.entity.Tote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToteRepository extends JpaRepository<Tote, Long> {

    Optional<Tote> findByBarcode(String barcode);

    List<Tote> findByStatusOrderByIdAsc(Tote.Status status);
}
