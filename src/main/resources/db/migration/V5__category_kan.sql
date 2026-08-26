-- 코리안넷 표준 분류(KAN) — 시연 상품이 쓰는 대분류 3개와 중분류 10개.
-- 기존 C10/C20 계열은 V2 seed 상품이 참조하므로 그대로 둔다.
INSERT INTO category (code, name, level, parent_code) VALUES
    ('01', '가공식품', 'LARGE', NULL),
    ('03', '일상용품', 'LARGE', NULL),
    ('05', '의약품/의료기기', 'LARGE', NULL),
    ('0101', '조미료', 'MEDIUM', '01'),
    ('0106', '통조림/병', 'MEDIUM', '01'),
    ('0112', '즉석/편의식품', 'MEDIUM', '01'),
    ('0116', '과자류', 'MEDIUM', '01'),
    ('0117', '음료류', 'MEDIUM', '01'),
    ('0121', '커피/코코아', 'MEDIUM', '01'),
    ('0123', '주류', 'MEDIUM', '01'),
    ('0311', '주방용품', 'MEDIUM', '03'),
    ('0320', '화장품', 'MEDIUM', '03'),
    ('0504', '의약외품', 'MEDIUM', '05')
ON CONFLICT (code) DO NOTHING;

-- 분류별 취급속성 기본값. 상품 실제 값과 같은 기준이다 — 액체·유리 용기만 파손주의.
INSERT INTO category_attribute_map
    (medium_category_code, default_refrigerate, default_fragile, default_irregular) VALUES
    ('0101', FALSE, TRUE, FALSE),
    ('0106', FALSE, TRUE, FALSE),
    ('0112', FALSE, FALSE, FALSE),
    ('0116', FALSE, FALSE, FALSE),
    ('0117', FALSE, TRUE, FALSE),
    ('0121', FALSE, TRUE, FALSE),
    ('0123', FALSE, TRUE, FALSE),
    ('0311', FALSE, FALSE, FALSE),
    ('0320', FALSE, FALSE, FALSE),
    ('0504', FALSE, FALSE, FALSE)
ON CONFLICT (medium_category_code) DO NOTHING;
