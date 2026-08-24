package com.awesome.backend.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * 중분류별 취급속성 기본값 (docs/03-erd.md §2).
 *
 * <p>촬영 API(1-3)가 상품의 중분류로 조회해 {@code handlingDefaults} 를 채우는 용도다.
 * 확정 후 최종 취급속성은 product 의 boolean 3개에 저장되고 이 테이블은 다시 참조되지 않는다.
 * 행이 없는 분류는 전부 false 로 취급한다.
 */
@Entity
@Table(name = "category_attribute_map")
public class CategoryAttributeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medium_category_code", referencedColumnName = "code", nullable = false, unique = true)
    private Category mediumCategory;

    @Column(name = "default_refrigerate", nullable = false)
    private boolean defaultRefrigerate;

    @Column(name = "default_fragile", nullable = false)
    private boolean defaultFragile;

    @Column(name = "default_irregular", nullable = false)
    private boolean defaultIrregular;

    protected CategoryAttributeMap() {
    }

    public Long getId() {
        return id;
    }

    public Category getMediumCategory() {
        return mediumCategory;
    }

    public boolean isDefaultRefrigerate() {
        return defaultRefrigerate;
    }

    public boolean isDefaultFragile() {
        return defaultFragile;
    }

    public boolean isDefaultIrregular() {
        return defaultIrregular;
    }
}
