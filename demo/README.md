# 시연 절차

서버를 띄우고 **런 시작 API 한 번**으로 시연 상태를 만든다. 그다음 출고지시를 넣어
주문이 배송단위로 쪼개지고 박스·라인·토트가 붙는 것을 보여준다.

## 0. 준비물

- Docker (Compose v2). 나머지는 컨테이너 안에서 돈다
- 이 저장소

## 1. 기동

```bash
docker compose up -d --build          # 첫 실행은 백엔드 빌드까지 해서 몇 분 걸린다
curl -s localhost:8000/actuator/health # {"status":"UP"} 이면 준비 완료
```

방금 다시 빌드했다면 헬스가 200이어도 **이전 컨테이너가 답한 것일 수 있다.**
`docker compose ps`로 backend가 방금 올라온 것인지 확인하고 넘어간다.

## 2. 런 시작

```bash
curl -s -X POST localhost:8000/api/v1/admin/demo/runs | python3 -m json.tool
```

```json
{
  "runId": "R20260825091500",
  "products": { "inbound": 3, "outbound": 3 },
  "queuedBatches": 3,
  "totes": { "idle": 10, "assigned": 0 },
  "boxTypes": { "count": 5, "restoredTo": 100 }
}
```

이 한 번으로 아래가 다 맞춰진다.

| 항목 | 결과 |
| --- | --- |
| 이전 런 | 진행 중이던 주문·배송단위는 종결(LOADED), 토트는 전부 유휴로 반납 |
| 박스 재고 | 100개로 복원 |
| 입고 풀 상품 3종 | 치수 미확정·재고 0 — 입고 시연에서 스캔·촬영·확정할 대상 |
| 출고 풀 상품 3종 | 치수 확정·재고 채움 — 출고지시가 부를 대상 |
| 출고지시 | 배치 3개가 대기열에 쌓임 (아직 접수 전) |

**아무것도 지우지 않는다.** 이전 런의 주문·배송단위는 종결 표시만 하고 남으므로,
리허설을 몇 번 돌려도 데이터가 사라지지 않고 이력이 쌓인다. 시연 DB에 다른 사람의
데이터가 있어도 안전하다.

런 아이디는 시작 시각이다. 사전순으로 정렬하면 시작한 순서가 된다.

## 3. 출고지시 접수

대기열에서 배치를 꺼내 넣는 API는 아직 없다(DM3). 지금은 대기열의 배치를 직접 꺼내
접수한다.

```bash
BATCH=$(docker compose exec -T db psql -U app -d app -tA -c \
  "select batch_json from demo_order_queue
    where released_at is null order by run_id desc, seq asc limit 1")
curl -s -X POST localhost:8000/api/v1/admin/orders/import \
     -H 'Content-Type: application/json' -d "$BATCH" | python3 -m json.tool
```

배치 3개를 순서대로 넣으면 케이스가 차례로 나온다.

| 배치 | 담긴 것 | 보여주는 것 |
| --- | --- | --- |
| 1 | 칩 2 + 라면 1 / 라면 8 | 합포장 1박스, 그리고 한 박스에 안 들어가 배송단위 2개로 분할 |
| 2 | 주스 2 + 칩 1 / 제주 배송 | 파손주의 → 완충재 권유, 그리고 없는 배송지역 거부 |
| 3 | 주스 12 | 재고보다 많은 요청 → 재고 부족 거부 |

거부된 주문은 나머지 주문의 접수를 막지 않는다. 응답 `rejected`에 사유와 수치가 실린다.

## 4. 저장 결과 확인

```bash
docker compose exec -T db psql -U app -d app -c "
select o.receipt_no, o.status as 주문상태, l.name as 라인, s.seq_no,
       bt.name as 추천박스, s.filler_recommended as 완충재,
       s.status as 배송단위상태, t.barcode as 토트
  from orders o
  join shipment s on s.order_id = o.id
  join line l on l.id = s.line_id
  join box_type bt on bt.id = s.recommended_box_id
  join tote_assignment ta on ta.shipment_id = s.id and ta.released_at is null
  join tote t on t.id = ta.tote_id
 order by o.receipt_no, s.seq_no;"
```

- **라인**: 배송지역으로 정해진다. 서울→1라인, 경기→2라인, 부산→3라인
- **한 주문이 두 줄**: 배송단위가 나뉜 경우. 각각 다른 박스와 다른 토트를 받는다
- **완충재 t**: 파손주의 상품이 들어간 배송단위
- **상태**: 주문 `ALLOCATED`, 배송단위 `TOTE_ASSIGNED` — 토트까지 붙어 작업자에게
  내보낼 준비가 끝났다는 뜻

토트 소진 현황:

```bash
docker compose exec -T db psql -U app -d app -c \
  "select status, count(*) from tote group by status order by status;"
```

토트는 10개다. 배치가 토트보다 많은 배송단위를 만들면 그 배치 전체가 되돌아가고 500이
난다 — 일부만 접수된 채로 남기지 않는다.

## 5. 다시 처음부터

2장을 다시 호출하면 된다. 지울 것도, 초기화 SQL도 없다.

시연 중 토트를 손으로 `ASSIGNED`로 바꿔 실패 장면을 보여줬더라도 런 시작이 전부
유휴로 되돌린다.

## 6. P2 화면으로 넘기기

여기서 만들어진 배송단위와 토트가 P2 검수·포장 화면의 입력이다.
지금 붙일 수 있는 통로는 라인별 배송단위 목록 하나다.

```bash
curl -s localhost:8000/api/v1/lines/1/shipments | python3 -m json.tool
```

```json
{"shipments": [
  {"shipmentId": 1, "receiptNo": "R20260825091500-R-DEMO-0001", "seqNo": 1,
   "status": "TOTE_ASSIGNED", "toteBarcode": "TOTE-001"}
]}
```

`shipmentId`는 접수할 때마다 새로 매겨진다.

**아직 없는 것**: 토트 바코드로 배송단위를 찾아 담을 품목·추천 박스·완충재 표시를
돌려주는 API. 발표 시나리오 슬라이드 5(포장 작업자 화면)가 이걸 쓰는데 아직 구현
전이다. 그 화면을 시연하려면 이 API가 먼저 필요하다.

## 7. 데이터 파일

시연에 쓰는 상품과 출고지시는 `demo/data/`에 있다.

| 파일 | 내용 |
| --- | --- |
| `products.json` | 상품 6종. `pool`이 `INBOUND`면 입고 시연 대상, `OUTBOUND`면 출고 대상 |
| `orders.json` | 출고지시 배치 목록. 배열 순서가 곧 투입 순서 |
| `images/{gtin}/cam{n}.jpg` | 입고 풀 상품의 촬영 이미지 |

파일을 고치고 런을 다시 시작하면 바뀐 내용으로 세팅된다. 서버가 읽는 위치는
`demo.data-dir` 설정값이고 기본값은 `./demo/data`다 — 로컬 실행과 컨테이너가 같은 경로를 쓴다.

주문번호는 런 아이디가 앞에 붙어 저장된다(`R20260825091500-R-DEMO-0001`). 같은 파일로
런을 몇 번 돌려도 주문번호가 겹치지 않고, 파일의 어느 주문인지도 남는다.

## 8. 안 되면 볼 곳

| 증상 | 원인 |
| --- | --- |
| 런 시작이 500, 메시지에 파일 경로 | `demo/data/`가 컨테이너에 없다. 이미지를 다시 빌드한다 |
| 접수가 400, detail에 `existingReceiptNos` | 같은 런의 배치를 두 번 넣었다. 런을 새로 시작하거나 다음 배치를 넣는다 |
| 접수가 400, detail에 `gtin` | 치수가 확정되지 않은 상품이다. 입고 풀 상품을 출고지시에 넣었는지 확인 |
| 접수가 500 | 유휴 토트가 없다. 런을 다시 시작하면 전부 반납된다 |
