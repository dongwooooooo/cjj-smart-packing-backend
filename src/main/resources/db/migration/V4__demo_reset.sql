-- =============================================================
-- V4: 시연 대기열에서 런 구분 제거
--
-- 런을 여러 개 쌓아두는 방식에서 "리셋 한 번으로 잔여물을 지우고 다시 세팅"으로
-- 바뀌었다. 대기열은 리셋 때 통째로 비워지므로 런 구분이 필요 없다.
-- V3은 이미 적용된 DB가 있어 수정하지 않고 여기서 걷어낸다.
-- =============================================================

DROP INDEX ux_demo_order_queue_run_seq;
ALTER TABLE demo_order_queue DROP COLUMN run_id;

-- 배치 순번은 이제 대기열 전체에서 유일하다
CREATE UNIQUE INDEX ux_demo_order_queue_seq ON demo_order_queue (seq);
