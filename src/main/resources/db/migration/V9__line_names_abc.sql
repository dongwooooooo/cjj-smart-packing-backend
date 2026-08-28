-- 라인 이름을 화면 표기와 맞춘다.
--
-- 시연 화면이 "LINE A~C" 로 부르는데 DB 는 "1라인~3라인" 이었다. 화면이 응답을 그대로
-- 쓰면 되도록 이름 쪽을 맞춘다. 지역 배정(SEOUL/GYEONGGI/BUSAN)은 그대로다.
--
-- V2 seed 를 고치지 않는 이유: 이미 적용된 DB 는 체크섬이 어긋나 기동이 막힌다.
-- 새 DB 는 V2 로 만들어진 뒤 이 파일이 바로 이어 돌아 같은 결과가 된다.
UPDATE line SET name = 'LINE A' WHERE name = '1라인';
UPDATE line SET name = 'LINE B' WHERE name = '2라인';
UPDATE line SET name = 'LINE C' WHERE name = '3라인';
