# 데이터셋 선별·추출 브리프 (윈도우 데이터셋 머신용)

## 목표

`C:\dataset\logistics_space_resized_512`에서 시연 상품을 선별하고, 이 레포 `demo/data/`가 읽는 형식(명세 `docs/demo-subsystem-spec.md` §2)으로 추출해 커밋한다. 결과가 들어오면 백엔드 리셋 API 한 번으로 시연 상태가 만들어진다.

## 0. 먼저 구조 파악 후 보고 (추출 전)

데이터셋 디렉토리 구조를 확인해 **먼저 회신**: `index/` 파일 종류와 컬럼(치수·무게·라벨·이미지 경로가 어디 있는지), 이미지 디렉토리 규칙(상품당 몇 장, 카메라 구분), svg 파일의 용도. 형식을 모른 채 추출하지 않는다.

## 1. 선별

1. 후보 전체에 추론 서버를 돌려 상품별 `정답 치수 / 추론 치수 / 신뢰도`를 표로 만든다
2. **입고 풀(INBOUND)**: 추론 오차가 작고 신뢰도가 높은 상품 3~5개. 촬영→확정 장면에서 게이트를 통과해야 한다. 이미지 3장이 있는 상품만
3. **출고 풀(OUTBOUND)**: 편성 장면이 나오는 치수 조합 5~8개 —
   - 합포장: 작은 상품 2~3종이 C호(내치수 34×25×21, 유효 31×22×18)에 함께 들어감
   - 분할: 한 상품 여러 개가 E호(48×38×34)를 넘김
   - 완충재: 파손주의 플래그 상품 1종 이상
   - 어느 상품도 E호 유효 내치수(45×35×31)를 넘지 않을 것 (초과 치수는 거부됨)
4. 선별 근거 표는 `demo/data/selection.md`로 함께 커밋 (상품별 정답·추론·신뢰도·풀·선정 이유)

## 2. 추출 형식

`demo/data/products.json` — 배열, 항목:

```json
{ "gtin": "13자리 바코드", "name": "상품명", "mediumCategoryCode": "C1010",
  "pool": "INBOUND | OUTBOUND",
  "dims": { "widthCm": 7.0, "lengthCm": 7.0, "heightCm": 23.0 },
  "weightKg": 1.08,
  "flags": { "refrigerate": false, "fragile": true, "irregular": false },
  "stockQty": 50,
  "images": ["images/8801234500011/cam1.jpg", "images/8801234500011/cam2.jpg", "images/8801234500011/cam3.jpg"],
  "inference": { "widthCm": 7.2, "lengthCm": 6.9, "heightCm": 22.6, "confidence": 0.93 } }
```

규칙:
- 바코드: 데이터셋에 GTIN이 없으면 `880` + 10자리 일련번호로 생성, 파일 전체 유일. 같은 바코드가 상품 마스터(`korean_net_master`)에도 들어가므로 이름·분류 필수. 분류 코드는 seed의 C1010/C1020/C2010/C2020 중 택
- 치수: cm, 소수 1자리. **축 규약(D-18)**: 높이는 실제 높이, 나머지 두 변은 긴 쪽이 `widthCm` — width ≥ length가 되게 정렬해 기록 (로더도 정렬하지만 파일부터 맞춘다)
- `dims`는 두 풀 모두 정답 치수. `inference`는 선택 필드 — 넣어두면 정답 vs 추론 비교 장면에 재추출 없이 쓴다
- 무게: kg 소수 3자리. 데이터셋에 무게가 없으면 별도 소스에서, 그것도 없으면 실측 또는 추정값 + selection.md에 출처 명시
- 재고: 출고 풀은 주문 수량을 넉넉히 넘기게(50 권장), 입고 풀은 0
- 이미지: 상품당 3장, 512px 리사이즈본, JPEG, `demo/data/images/{gtin}/cam1.jpg`~`cam3.jpg`. 입고 풀은 필수, 출고 풀은 있으면 넣고 없으면 빈 배열

`demo/data/orders.json` — 배치 배열. 지금 샘플 구조 그대로 두고 `gtin`·`qty`만 출고 풀 상품으로 교체. 배치당 장면 하나(합포장 / 분할 / 완충재). `receiptNo`는 파일 전체 유일, `regionCode`는 SEOUL/GYEONGGI/BUSAN.

## 3. 검증 (필수)

```
./gradlew test --tests 'com.awesome.backend.demo.*'
```

로더가 형식·유일성·축 규약을 검사하고, 데이터 검증 테스트가 편성 엔진을 실제로 돌려 **합포장 1박스 / 분할 2개 이상 / 완충재 켜짐**이 그 상품들로 나오는지 확인한다. 실패하면 치수 조합을 조정한다. Docker가 있으면 `docker compose up` → `POST /api/v1/admin/demo/reset` → `GET /api/v1/admin/demo/status`까지.

## 4. 커밋·회신

- 브랜치 `feat/demo-dataset`, base `develop`. `demo/data/` 파일만 변경 (코드 수정 금지). 커밋에 세션 링크 트레일러 넣지 않음
- 푸시 후 조율 세션("조직 레포 세팅 및 백엔드 2단계 초기화")에 회신: 선별 결과 요약(풀별 상품 수·선정 기준), 테스트 결과, 미해결(무게 출처 등)

## 5. 선택 — 전체 데이터셋 S3 보관

팀 공유·추론 입력용. 버킷 예: `s3://cj-ai-sw-dataset/logistics_space_resized_512/`, `aws s3 sync` 한 번. **AWS 키를 명령 인자에 넣지 말 것** — `aws configure`(대화형) 또는 자격 증명 파일 편집만. 키 값이 대화·로그에 찍히면 재발급 대상이 된다.
