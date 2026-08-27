-- V2 seed 의 상품 6종을 "시연 상품" 에서 "테스트 픽스처" 로 분리한다.
--
-- 실제 데이터셋으로 교체된 뒤에도 남아 화면의 상품 수를 부풀렸다. 그렇다고 지우면
-- 이 GTIN 을 쓰는 통합 테스트 20여 곳이 시연 데이터에 다시 묶인다 — 데이터가 바뀔
-- 때마다 테스트가 깨지는 결합을 만드는 셈이다.
--
-- 그래서 지우지 않고 이름에 표시만 남긴다. 화면은 데모 풀(demo_product)만 보므로
-- 이 상품들은 시연에 나타나지 않고, 테스트는 그대로 쓴다. 재고는 0 으로 되돌린다 —
-- 리셋이 원장을 비우므로 캐시만 남아 있으면 합계와 어긋난다.
UPDATE product
   SET name = '[테스트] ' || name,
       stock_qty = 0,
       updated_at = now()
 WHERE gtin LIKE '88012345%'
   AND name NOT LIKE '[테스트]%';

UPDATE korean_net_master
   SET product_name = '[테스트] ' || product_name
 WHERE gtin LIKE '88012345%'
   AND product_name NOT LIKE '[테스트]%';
