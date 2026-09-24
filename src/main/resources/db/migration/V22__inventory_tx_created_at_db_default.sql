-- 집계 정착 창(StockBalanceCollector)의 기준 시각. Java 시각 대신 DB 가 행을 만드는 순간의
-- 시계(clock_timestamp())를 기록한다. V1 의 기본값 now() 는 트랜잭션 시작 시각이라, 오래 열린
-- 트랜잭션이 늦게 넣은 행이 "이미 정착한 행"으로 보여 더 작은 id 를 가진 미커밋 행을 건너뛸 수 있다.
-- clock_timestamp() 는 id(nextval) 를 받는 시점과 같은 순간이라 "created_at 이 창보다 오래됐다
-- = 그보다 작은 id 를 받은 트랜잭션도 창보다 오래 열려 있다" 가 성립한다.
ALTER TABLE inventory_tx ALTER COLUMN created_at SET DEFAULT clock_timestamp();
