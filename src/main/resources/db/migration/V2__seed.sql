-- =============================================================
-- V2: seed — docs/05-team-plan.md Phase 1
--   category / region / line 3개 (D-12) / box_type A~E호 /
--   tote / 시연 상품 무게 매핑 (D-10)
-- =============================================================

-- 분류: 대분류 2 + 중분류 4 (액체주의 파생 규칙의 '음료류' 포함)
INSERT INTO category (code, name, level, parent_code) VALUES
    ('C10',   '음료',     'LARGE',  NULL),
    ('C20',   '가공식품', 'LARGE',  NULL),
    ('C1010', '과채주스', 'MEDIUM', 'C10'),
    ('C1020', '탄산음료', 'MEDIUM', 'C10'),
    ('C2010', '과자',     'MEDIUM', 'C20'),
    ('C2020', '라면',     'MEDIUM', 'C20');

-- 분류별 취급속성 기본값 (없는 분류는 전부 false 취급)
INSERT INTO category_attribute_map
    (medium_category_code, default_refrigerate, default_fragile, default_irregular) VALUES
    ('C1010', TRUE,  TRUE,  FALSE),
    ('C1020', FALSE, TRUE,  FALSE),
    ('C2010', FALSE, FALSE, FALSE),
    ('C2020', FALSE, FALSE, FALSE);

-- 지역 + 라인 3개 (D-12: 라인은 3개, 지역 배정은 추후 결정)
INSERT INTO region (code, name) VALUES
    ('SEOUL',    '서울'),
    ('GYEONGGI', '경기'),
    ('BUSAN',    '부산');

INSERT INTO line (name, region_code, status) VALUES
    ('1라인', 'SEOUL',    'ACTIVE'),
    ('2라인', 'GYEONGGI', 'ACTIVE'),
    ('3라인', 'BUSAN',    'ACTIVE');

-- 박스 A~E호 (내치수 cm — 우체국 소포 1~5호 규격 준용)
INSERT INTO box_type (name, inner_width_cm, inner_length_cm, inner_height_cm, stock_qty) VALUES
    ('A호', 22.0, 19.0,  9.0, 100),
    ('B호', 27.0, 18.0, 15.0, 100),
    ('C호', 34.0, 25.0, 21.0, 100),
    ('D호', 41.0, 31.0, 28.0, 100),
    ('E호', 48.0, 38.0, 34.0, 100);

-- 토트 10개
INSERT INTO tote (barcode, status) VALUES
    ('TOTE-001', 'IDLE'), ('TOTE-002', 'IDLE'), ('TOTE-003', 'IDLE'),
    ('TOTE-004', 'IDLE'), ('TOTE-005', 'IDLE'), ('TOTE-006', 'IDLE'),
    ('TOTE-007', 'IDLE'), ('TOTE-008', 'IDLE'), ('TOTE-009', 'IDLE'),
    ('TOTE-010', 'IDLE');

-- 코리안넷 스냅샷 (시연 상품 6종)
INSERT INTO korean_net_master
    (gtin, product_name, medium_category_code, image_url, batch_id, imported_at) VALUES
    ('8801234500011', '델가 오렌지주스 1L',   'C1010', 'https://placehold.co/300?text=juice',  'SEED-V2', now()),
    ('8801234500028', '델가 포도주스 1L',     'C1010', 'https://placehold.co/300?text=grape',  'SEED-V2', now()),
    ('8801234500035', '스파클 사이다 500ml',  'C1020', 'https://placehold.co/300?text=cider',  'SEED-V2', now()),
    ('8801234500042', '허니버터칩 60g',       'C2010', 'https://placehold.co/300?text=chip',   'SEED-V2', now()),
    ('8801234500059', '초코파이 12입',        'C2010', 'https://placehold.co/300?text=pie',    'SEED-V2', now()),
    ('8801234500066', '매운라면 5입',         'C2020', 'https://placehold.co/300?text=ramen',  'SEED-V2', now());

-- 시연 상품 무게 매핑 (D-10): 저울 하드웨어 대신 product.weight_kg 사전 등록값을
-- 촬영(1-3) 시점에 조회해 반환한다. dim_status=NONE — 치수는 시연 중 촬영·확정으로 채운다.
INSERT INTO product
    (gtin, name, medium_category_code, image_url, source, weight_kg, dim_status,
     is_refrigerate, is_fragile, is_irregular) VALUES
    ('8801234500011', '델가 오렌지주스 1L',  'C1010', 'https://placehold.co/300?text=juice', 'MASTER', 1.080, 'NONE', TRUE,  TRUE,  FALSE),
    ('8801234500028', '델가 포도주스 1L',    'C1010', 'https://placehold.co/300?text=grape', 'MASTER', 1.075, 'NONE', TRUE,  TRUE,  FALSE),
    ('8801234500035', '스파클 사이다 500ml', 'C1020', 'https://placehold.co/300?text=cider', 'MASTER', 0.540, 'NONE', FALSE, TRUE,  FALSE),
    ('8801234500042', '허니버터칩 60g',      'C2010', 'https://placehold.co/300?text=chip',  'MASTER', 0.068, 'NONE', FALSE, FALSE, FALSE),
    ('8801234500059', '초코파이 12입',       'C2010', 'https://placehold.co/300?text=pie',   'MASTER', 0.468, 'NONE', FALSE, FALSE, FALSE),
    ('8801234500066', '매운라면 5입',        'C2020', 'https://placehold.co/300?text=ramen', 'MASTER', 0.600, 'NONE', FALSE, FALSE, FALSE);
