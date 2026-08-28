-- 포장 시연에서 이미 내준 토트를 표시한다.
--
-- 입고 쪽 바코드 통로와 같은 문제를 푼다 — "포장 전인 배송단위" 로만 판단하면 화면이
-- 버튼을 다시 눌렀을 때 같은 토트가 계속 나온다. 내준 시각을 남겨 진행 위치를 서버가
-- 들고 있게 한다. 리셋이 이 표를 비운다.
--
-- 배송단위 행에 컬럼을 더하지 않고 별도 표로 둔다. 시연에서만 쓰는 표시라 포장 화면이
-- 읽는 배송단위 정보에는 섞이지 않아야 한다.
CREATE TABLE demo_served_tote (
    shipment_id BIGINT    PRIMARY KEY,
    served_at   TIMESTAMP NOT NULL DEFAULT now()
);
