package com.awesome.backend.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * 코리안넷 스냅샷 (docs/03-erd.md §2).
 *
 * <p>사전 배치로 내려받은 로컬 상품 마스터다. 서비스 로직은 여기에 쓰지 않는다 —
 * 적재는 {@code POST /admin/master-snapshot/import} 배치만 수행한다.
 */
@Entity
@Table(name = "korean_net_master")
public class KoreanNetMaster {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "gtin", length = 13, nullable = false)
    private String gtin;

    @Column(name = "product_name", length = 200, nullable = false)
    private String productName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medium_category_code", referencedColumnName = "code", nullable = false)
    private Category mediumCategory;

    /** 1단 표시용 상품 이미지. 스냅샷에 없을 수 있어 nullable 이다. */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "batch_id", length = 50, nullable = false)
    private String batchId;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    protected KoreanNetMaster() {
    }

    public String getGtin() {
        return gtin;
    }

    public String getProductName() {
        return productName;
    }

    public Category getMediumCategory() {
        return mediumCategory;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getBatchId() {
        return batchId;
    }

    public LocalDateTime getImportedAt() {
        return importedAt;
    }
}
