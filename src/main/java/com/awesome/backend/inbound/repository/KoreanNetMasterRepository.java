package com.awesome.backend.inbound.repository;

import com.awesome.backend.inbound.entity.KoreanNetMaster;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 코리안넷 스냅샷 조회. 적재는 Admin 배치만 수행한다. */
public interface KoreanNetMasterRepository extends JpaRepository<KoreanNetMaster, String> {

    Optional<KoreanNetMaster> findByGtin(String gtin);
}
