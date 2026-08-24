package com.awesome.backend.inbound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * 센터 상품 (SKU). 테이블 주인은 P1 — 이 매핑은 P2·P3이 쓰는 읽기 컬럼과
 * 재고 캐시(stock_qty)까지만 담았다. 측정·등록 관련 컬럼은 P1이 확장한다.
 * stock_qty 갱신은 inventory의 StockMovementRecorder 단일 창구로만 한다.
 *
 * <p>medium_category_code, dim_status, dim_method 는 다른 엔티티(Category 등)를
 * 객체로 물지 않고 코드/문자열 값으로만 들고 있다 — 도메인 간 참조를 값으로만
 * 결합하는 지금 컨벤션에 맞춘 것이다.
 */
@Entity
@Table(name = "product")
public class Product {

    /** image_url 이 NOT NULL 이라 마스터에 이미지가 없거나 수기 등록(1-2)에도 값이 필요하다. */
    public static final String PLACEHOLDER_IMAGE_URL = "https://placehold.co/300?text=no-image";

    public static final String DIM_STATUS_NONE = "NONE";
    public static final String DIM_STATUS_CONFIRMED = "CONFIRMED";

    // 스키마(V1 CHECK)에는 'MANUAL' 값이 남아 있지만 만드는 경로가 없다 — 유일한 생성처였던
    // 1-2 가 D-21 로 삭제됐다. ENUM 정리는 마이그레이션 수정 + 볼륨 재적재가 필요해 후속 과제.
    public static final String SOURCE_MASTER = "MASTER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(nullable = false, unique = true, columnDefinition = "char(13)")
    private String gtin;

    @Column(nullable = false)
    private String name;

    @Column(name = "medium_category_code", nullable = false)
    private String mediumCategoryCode;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "width_cm")
    private BigDecimal widthCm;

    @Column(name = "length_cm")
    private BigDecimal lengthCm;

    @Column(name = "height_cm")
    private BigDecimal heightCm;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @Column(name = "is_refrigerate", nullable = false)
    private boolean refrigerate;

    @Column(name = "is_fragile", nullable = false)
    private boolean fragile;

    @Column(name = "is_irregular", nullable = false)
    private boolean irregular;

    @Column(name = "dim_status", nullable = false)
    private String dimStatus = DIM_STATUS_NONE;

    @Column(name = "dim_method")
    private String dimMethod;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    protected Product() {
    }

    private Product(String gtin, String name, String mediumCategoryCode, String imageUrl, String source) {
        this.gtin = gtin;
        this.name = name;
        this.mediumCategoryCode = mediumCategoryCode;
        this.imageUrl = imageUrl;
        this.source = source;
        this.dimStatus = DIM_STATUS_NONE;
    }

    /**
     * 코리안넷 마스터에 있는 바코드를 처음 스캔했을 때 생성한다 (1-1 의 NEW 분기).
     * 치수는 아직 없으므로 dimStatus = NONE 으로 시작한다.
     */
    public static Product fromMaster(KoreanNetMaster master, String imageUrl) {
        return new Product(master.getGtin(), master.getProductName(),
                master.getMediumCategory().getCode(), imageUrl, SOURCE_MASTER);
    }

    public boolean hasConfirmedDimensions() {
        return DIM_STATUS_CONFIRMED.equals(dimStatus);
    }

    public Long id() {
        return id;
    }

    public String gtin() {
        return gtin;
    }

    public String name() {
        return name;
    }

    public String mediumCategoryCode() {
        return mediumCategoryCode;
    }

    public String imageUrl() {
        return imageUrl;
    }

    public String source() {
        return source;
    }

    public BigDecimal widthCm() {
        return widthCm;
    }

    public BigDecimal lengthCm() {
        return lengthCm;
    }

    public BigDecimal heightCm() {
        return heightCm;
    }

    public BigDecimal weightKg() {
        return weightKg;
    }

    public boolean refrigerate() {
        return refrigerate;
    }

    public boolean fragile() {
        return fragile;
    }

    public boolean irregular() {
        return irregular;
    }

    public String dimStatus() {
        return dimStatus;
    }

    public String dimMethod() {
        return dimMethod;
    }

    public int stockQty() {
        return stockQty;
    }

    public void changeStockQty(int stockQty) {
        this.stockQty = stockQty;
    }
}
