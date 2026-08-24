package com.awesome.backend.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 센터 상품(SKU) — 바코드 스캔 3분기 판정의 기준 테이블 (docs/03-erd.md §2).
 *
 * <p>쓰기는 P1 전용이다. P2·P3 은 읽기만 하며, 읽을 때는
 * {@code @Transactional(readOnly = true)} 를 사용해 JPA 의 자동 반영을 막는다 (docs/05 §3).
 *
 * <p>치수는 축 규약(D-15)을 따른다 — height 는 실제 높이, 나머지 두 변은 긴 쪽이 width.
 * 불변식 {@code widthCm >= lengthCm} 는 {@link #confirmMeasurement} 안에서 보장한다.
 */
@Entity
@Table(name = "product")
public class Product {

    /**
     * 이미지가 없는 경로에서 쓰는 대체 주소. {@code image_url} 이 NOT NULL 이라
     * 수기 등록(1-2)이나 마스터에 이미지가 없는 상품에도 값이 필요하다.
     */
    public static final String PLACEHOLDER_IMAGE_URL = "https://placehold.co/300?text=no-image";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "gtin", length = 13, nullable = false, unique = true)
    private String gtin;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medium_category_code", referencedColumnName = "code", nullable = false)
    private Category mediumCategory;

    @Column(name = "image_url", length = 500, nullable = false)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    private ProductSource source;

    @Column(name = "width_cm", precision = 5, scale = 1)
    private BigDecimal widthCm;

    @Column(name = "length_cm", precision = 5, scale = 1)
    private BigDecimal lengthCm;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    /** 시연에서는 저울 하드웨어 대신 이 사전 등록값을 촬영 시점에 조회해 반환한다 (D-10). */
    @Column(name = "weight_kg", precision = 6, scale = 3)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(name = "dim_status", length = 10, nullable = false)
    private DimStatus dimStatus = DimStatus.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "dim_method", length = 10)
    private DimMethod dimMethod;

    @Column(name = "is_refrigerate", nullable = false)
    private boolean isRefrigerate;

    @Column(name = "is_fragile", nullable = false)
    private boolean isFragile;

    @Column(name = "is_irregular", nullable = false)
    private boolean isIrregular;

    /** 원장(inventory_tx) 합계의 캐시. 같은 트랜잭션 안에서 함께 갱신한다. */
    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Product() {
    }

    private Product(String gtin, String name, Category mediumCategory, String imageUrl,
                    ProductSource source, BigDecimal weightKg) {
        this.gtin = gtin;
        this.name = name;
        this.mediumCategory = mediumCategory;
        this.imageUrl = imageUrl;
        this.source = source;
        this.weightKg = weightKg;
        this.dimStatus = DimStatus.NONE;
    }

    /**
     * 코리안넷 마스터에 있는 바코드를 처음 스캔했을 때 생성한다 (1-1 의 NEW 분기).
     * 치수는 아직 없으므로 {@code dimStatus = NONE} 으로 시작한다.
     */
    public static Product fromMaster(KoreanNetMaster master, String imageUrl) {
        return new Product(master.getGtin(), master.getProductName(),
                master.getMediumCategory(), imageUrl, ProductSource.MASTER, null);
    }

    /** 미등록 바코드를 작업자가 수기 등록했을 때 생성한다 (1-2). */
    public static Product manual(String gtin, String name, Category mediumCategory, String imageUrl) {
        return new Product(gtin, name, mediumCategory, imageUrl, ProductSource.MANUAL, null);
    }

    /**
     * 측정 확정 결과를 반영한다 (1-4). 재고는 건드리지 않는다 —
     * 재고 증가는 수량 입고(1-5)가 유일한 경로다 (D-09).
     *
     * <p>가로·세로는 축 규약(D-15)에 맞춰 정렬해 저장한다. 높이는 그대로 둔다.
     */
    public void confirmMeasurement(BigDecimal widthCm, BigDecimal lengthCm, BigDecimal heightCm,
                                   BigDecimal weightKg, DimMethod dimMethod,
                                   boolean refrigerate, boolean fragile, boolean irregular) {
        boolean swap = widthCm.compareTo(lengthCm) < 0;
        this.widthCm = swap ? lengthCm : widthCm;
        this.lengthCm = swap ? widthCm : lengthCm;
        this.heightCm = heightCm;
        this.weightKg = weightKg;
        this.dimMethod = dimMethod;
        this.dimStatus = DimStatus.CONFIRMED;
        this.isRefrigerate = refrigerate;
        this.isFragile = fragile;
        this.isIrregular = irregular;
    }

    /** 수량 입고 (1-5). 재고 증가의 유일한 경로다. */
    public void increaseStock(int qty) {
        this.stockQty += qty;
    }

    /** 포장 완료 차감 (3-8). 부족하면 호출 전에 걸러야 한다. */
    public void decreaseStock(int qty) {
        this.stockQty -= qty;
    }

    /** 마스터 스냅샷에 동일 GTIN 이 들어왔을 때의 병합 — 이름·분류만 갱신하고 치수·재고는 보존한다. */
    public void mergeFromMaster(KoreanNetMaster master) {
        this.name = master.getProductName();
        this.mediumCategory = master.getMediumCategory();
        this.source = ProductSource.MASTER;
    }

    public boolean hasConfirmedDimensions() {
        return dimStatus == DimStatus.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public String getGtin() {
        return gtin;
    }

    public String getName() {
        return name;
    }

    public Category getMediumCategory() {
        return mediumCategory;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public ProductSource getSource() {
        return source;
    }

    public BigDecimal getWidthCm() {
        return widthCm;
    }

    public BigDecimal getLengthCm() {
        return lengthCm;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public DimStatus getDimStatus() {
        return dimStatus;
    }

    public DimMethod getDimMethod() {
        return dimMethod;
    }

    public boolean isRefrigerate() {
        return isRefrigerate;
    }

    public boolean isFragile() {
        return isFragile;
    }

    public boolean isIrregular() {
        return isIrregular;
    }

    public int getStockQty() {
        return stockQty;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
