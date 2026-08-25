# 시연 데이터 서브시스템 — 요구사항 명세 v1.0

P3 담당(admin·seed 범위). 준비 SQL 방식(demo/prepare-demo.sql)을 대체한다. 계약 변경분은 docs 02·04에 반영.

## 0. 목적

서버를 띄우고 **초기 세팅 API 한 번**으로 시연 상태를 만든다. 상품 데이터의 출처는 실제 데이터셋(`logistics_space_resized_512`: 이미지 + 가로·세로·높이, 무게는 별도 소스)이다. 시연 중에는 출고지시를 한 번에 밀어넣지 않고 **대기열에서 순차 투입**해 화면이 실시간처럼 움직이게 한다.

## 1. 상품 두 풀 [확정 — 2026-08-25]

| 풀 | 용도 | 리셋 후 상태 |
| --- | --- | --- |
| INBOUND | 입고 시연 — 신규 상품 스캔→촬영→확정 장면 | 마스터·상품 행 존재, 치수 미확정(dim_status=NONE), 재고 0. 데이터셋 이미지·정답 치수는 데모 테이블에 대기 |
| OUTBOUND | 출고지시·포장 시연 | 치수 확정(데이터셋 정답치 적재), 재고 세팅 |

선정 기준: INBOUND 풀은 데이터셋에서 **추론이 잘 되는 상품**을 고른다(AI팀·사용자 선정). OUTBOUND 풀은 편성 시연 케이스(합포장·분할·완충재·거부)를 만들 수 있는 치수 조합.

## 2. 데이터 파일 [확정 — 레포 포함 방식]

데이터셋 머신에서 추출해 레포 `demo/data/`에 넣는다. 서버는 데이터셋 경로에 의존하지 않는다.

```
demo/data/products.json      상품 목록 (아래 형식)
demo/data/images/{gtin}/cam1.jpg, cam2.jpg, cam3.jpg   INBOUND 풀 이미지 (512px 리사이즈본)
demo/data/orders.json        출고지시 배치 목록 — 투입 순서대로 배열 (기존 orders-batch.json 형식의 배열)
```

products.json 항목:

```json
{ "gtin": "8801234500011", "name": "델가 오렌지주스 1L", "mediumCategoryCode": "C1010",
  "pool": "INBOUND",
  "dims": { "widthCm": 7.0, "lengthCm": 7.0, "heightCm": 23.0 },
  "weightKg": 1.080,
  "flags": { "refrigerate": true, "fragile": true, "irregular": false },
  "stockQty": 0,
  "images": ["images/8801234500011/cam1.jpg", "..."] }
```

- `dims`는 두 풀 모두 필수 — INBOUND는 정답치(추론 비교·mock 기준)로 데모 테이블에, OUTBOUND는 상품 확정치로 적재
- 치수 축 규약(D-18) 적용: width ≥ length가 되도록 추출 시 정렬
- ⚠️ 추출 스크립트는 데이터셋 머신에서 실행 — 이 명세는 산출 파일 형식만 정한다
- **서버 접근 [확정]**: `demo.data-dir` 프로퍼티(기본 `./demo/data`)로 읽는다. Dockerfile이 `demo/`를 이미지에 COPY — 컨테이너 WORKDIR 기준 같은 상대 경로로 동작. 클래스패스 이동·볼륨 마운트는 기각 (경로 규칙이 갈라지거나 실행 방법이 늘어남)
- orders.json의 `receiptNo`는 **파일 전체(모든 배치) 통틀어 유일** — 로더가 검증한다. 실제 주문번호는 런 시작 시 서버가 만든다 (§4-4)

## 3. 데모 테이블 (V3 마이그레이션) [제안]

```
demo_product(gtin PK → product.gtin, pool, gt_width_cm, gt_length_cm, gt_height_cm, image_dir)
demo_order_queue(id PK, run_id, seq, batch_json JSONB, released_at NULL)
```

- `demo_product`는 **P1 접점**: 촬영(1-3) 시 P1의 추론 클라이언트가 이 행을 찾으면 이미지 디렉토리를 모델 서버에 보내고, mock이면 정답치 기준으로 흔든다. P1 측 변경은 이 테이블을 읽는 것뿐 — 협의 필요 (§6)
- 시연 전용 테이블임을 이름으로 드러낸다. 운영 스키마와 분리

## 4. 새 런 시작 API [확정 — 2026-08-25, 삭제 없음]

`POST /api/v1/admin/demo/runs` → 런 ID 발급 (예: `R3`), 단일 트랜잭션. **아무것도 삭제하지 않는다.**

1. 이전 런 종결 — 활성 토트 할당 해제(released_at 기록), 토트 전부 IDLE. 진행 중(PLANNED·TOTE_ASSIGNED·PACKING) 배송단위와 그 주문은 LOADED로 종결(취소 상태가 없어 시연 범위 밖 종료 상태를 빌린다). 미확정 측정 세션은 DISCARDED
2. 박스 재고 seed값으로 복원 (값 갱신)
3. products.json 적재 — 마스터·상품 **upsert**. INBOUND: dim_status=NONE·치수 NULL·재고 0·demo_product 갱신 / OUTBOUND: 치수 CONFIRMED·재고를 목표값으로 맞추는 원장 ADJUST 기록
4. orders.json → demo_order_queue 적재 (run_id, seq=배치 순번). **주문번호 = `{runId}-{파일 receiptNo}`** (예: `R3-DEMO-0001`) — 런마다 유일하고 파일의 어느 주문인지 추적 가능. 치환은 큐 적재 시점에 batch_json에 반영
5. 응답: runId, 풀별 상품 수, 대기 배치 수, 토트·박스 상태 요약

멱등: 연속 호출은 새 런을 하나 더 만들 뿐, 이전 런 데이터는 이력으로 남는다.

## 5. 출고지시 투입 API [제안]

- `POST /api/v1/admin/demo/orders/next` — 대기열 맨 앞 배치 1개를 꺼내 기존 import(§orders-import-spec)로 접수, released_at 기록. 응답은 import 응답 + 남은 대기 수
- `POST /api/v1/admin/demo/orders/auto?intervalSeconds=N` / `DELETE .../auto` — 스케줄러로 N초마다 next 실행 (시연 중 자동 흐름). 서버 재시작 시 꺼짐
- 대기열 비면 next는 204

## 6. P1 접점 계약 [협의 필요]

| 항목 | P3 제공 | P1 사용 |
| --- | --- | --- |
| 이미지 | `demo/data/images/{gtin}/cam{n}.jpg` + `demo_product.image_dir` | 촬영(1-3) 시 모델 서버 호출 입력, 응답의 images URL |
| 정답 치수 | `demo_product.gt_*` | mock 추론의 기준값 (설정값 대신) |
| 무게 | `product.weight_kg` (기존 D-10 그대로) | 변경 없음 |

## 7. 작업 단위

| # | 단위 | 산출물 |
| --- | --- | --- |
| DM1 | 데이터 파일 형식 확정 + V3 데모 테이블 + 샘플 products.json(기존 6종으로) | 마이그레이션, 샘플 파일 |
| DM2 | 새 런 시작 API | 컨트롤러·서비스·통합 테스트 |
| DM3 | 출고지시 큐 + next/auto API | 〃 |
| DM4 | P1 접점 협의·문서 반영(02·04) | 문서 |
| — | 실제 데이터셋 추출 (데이터셋 머신, 사용자·AI팀) | products.json·이미지·orders.json |

기존 demo/prepare-demo.sql·README는 DM2 머지 시 제거. 런 방식 결정 근거: 파괴적 조작 없이 리허설 반복, 이력 보존, 시연 DB에 타 데이터가 있어도 안전.
