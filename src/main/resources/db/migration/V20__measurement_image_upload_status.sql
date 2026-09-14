-- 사진 업로드를 트랜잭션 밖·응답 뒤로 뺀 뒤, 세션이 커밋된 시점에는 보관소에 객체가 아직 없다.
-- 업로드가 끝났는지를 행마다 남겨 1-6 조회가 없는 객체의 임시 주소를 발급하지 않게 한다.
-- PENDING = 업로드 대기·진행 중, STORED = 보관소에 있음, FAILED = 재시도까지 실패
--
-- 번호가 V13 이 아닌 V20 인 이유: 다른 작업 갈래가 V13 을 쓰고 있어 충돌을 피한다.
ALTER TABLE measurement_image
    ADD COLUMN upload_status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (upload_status IN ('PENDING', 'STORED', 'FAILED'));

-- 이미 쌓인 행은 동기 업로드로 저장된 것이라 보관소에 객체가 있다.
UPDATE measurement_image SET upload_status = 'STORED';
