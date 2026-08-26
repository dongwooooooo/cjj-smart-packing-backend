-- 토트 30개 추가 (기존 10개 → 40개).
--
-- 시연이 출고지시를 18배치까지 밀어 넣으면서 주문이 여러 건으로 늘었다. 접수는 주문마다
-- 유휴 토트를 하나씩 잡는데, 토트가 모자라면 배치 전체가 롤백된다(의도된 동작).
-- 포장을 완료하면 토트는 다시 유휴로 돌아오지만, 시연에서는 접수를 먼저 몰아넣고
-- 포장을 뒤에 보여주므로 그 사이를 버틸 수량이 필요하다.
INSERT INTO tote (barcode, status)
SELECT 'TOTE-' || LPAD(n::text, 3, '0'), 'IDLE'
  FROM generate_series(11, 40) AS n
ON CONFLICT (barcode) DO NOTHING;
