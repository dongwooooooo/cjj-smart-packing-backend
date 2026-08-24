package com.awesome.backend.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 코리안넷 표준 분류 (docs/03-erd.md §2).
 *
 * <p>대분류·중분류를 한 테이블에서 자기참조로 표현한다. 다른 테이블은 중분류 코드 하나만
 * 들고 있고, 대분류는 {@link #parent} 를 타고 조회한다 — 대분류를 중복 저장하면 계층이
 * 어긋날 수 있기 때문이다.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @Column(name = "code", length = 20, nullable = false)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", length = 10, nullable = false)
    private CategoryLevel level;

    /** 중분류가 소속된 대분류. 대분류 행은 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_code", referencedColumnName = "code")
    private Category parent;

    protected Category() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public CategoryLevel getLevel() {
        return level;
    }

    public Category getParent() {
        return parent;
    }

    /** 대분류 코드. 자신이 대분류면 자기 코드를 돌려준다. */
    public String getLargeCode() {
        return parent == null ? code : parent.getCode();
    }

    /** 대분류명. 자신이 대분류면 자기 이름을 돌려준다. */
    public String getLargeName() {
        return parent == null ? name : parent.getName();
    }
}
