-- 토트 40 → 100.
--
-- 주문 하나가 박스에 안 들어가면 배송단위로 쪼개지고, 배송단위마다 토트가 하나씩 붙는다.
-- 그래서 필요한 토트 수는 주문 수가 아니라 배송단위 수를 따라간다. CJ 30품목으로 바꾸면서
-- 라인마다 배송내역을 채우려고 주문을 40건으로 늘렸는데, 40개로는 대기열을 다 투입하기 전에
-- 유휴 토트가 떨어져 투입이 멈췄다.
--
-- 넉넉히 잡는 쪽이 맞다 — 토트 행은 시연에서 상태만 오갈 뿐 다른 표를 건드리지 않는다.
INSERT INTO tote (barcode, status)
SELECT 'TOTE-' || LPAD(n::text, 3, '0'), 'IDLE'
  FROM generate_series(41, 100) AS n
ON CONFLICT (barcode) DO NOTHING;
