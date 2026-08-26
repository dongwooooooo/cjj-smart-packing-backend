# backend

풀필먼트 검수-포장 판단 시스템 백엔드. 명세의 단일 출처는 [docs 저장소](https://github.com/cj-ai-sw/docs)다 — 코드와 문서가 어긋나면 문서를 먼저 고치고 코드를 맞춘다.

## 스택

| 구성요소 | 버전 |
| --- | --- |
| Java | 25 (toolchain — 로컬 미설치 시 Gradle이 자동 다운로드) |
| Spring Boot | 4.1.0 |
| Gradle | 9.7.1 (wrapper) |
| PostgreSQL | 18.6 (compose) |
| Flyway | Boot BOM 결정 (`spring-boot-flyway` 모듈 필수 — 빠지면 마이그레이션이 조용히 안 돈다) |
| springdoc-openapi | 3.1.0 |

## 실행

```bash
cp .env.example .env        # 기본값 그대로 동작
docker compose up --build   # db(override) + backend
```

| 주소 | 내용 |
| --- | --- |
| http://localhost:8000/actuator/health | 상태 (`{"status":"UP"}`) |
| http://localhost:8000/swagger-ui.html | API 문서 (service / admin 그룹) |
| `localhost:5432` | DB (계정 `app` / `app`) |

DB만 컨테이너로 띄우고 코드는 직접 실행하려면:

```bash
docker compose up -d db
./gradlew bootRun
```

## 구조

```
src/main/java/com/awesome/backend/
  common/error/     공통 에러 포맷 (02-api-spec §0) — ErrorCode, ApiException, GlobalExceptionHandler
  common/config/    Swagger 그룹 (service = /api/v1/**, admin = /api/v1/admin/**)
  inbound/          P1 입고 도메인 (스캔·측정·수량입고)
  outbound/         P2 출고 포장 도메인 (토트 스캔·포장완료)
  orders/           P3 출고지시·주문 도메인
    packing/        카토나이제이션 — PackingEngine(배치 판정), Cartonizer(편성), BlockFactory
  inventory/        재고 도메인 (증감·조회 — InventoryService 창구)
  dashboard/        관리자 대시보드 (2차 MVP)

각 도메인 내부는 controller/service/repository/entity 4계층으로 나뉜다 (orders/packing 같은 알고리즘 모듈은 예외). 타 도메인 접근은 그 도메인의 service·repository를 직접 호출한다.

엔티티 배치 — 테이블 소유(docs/05 §1)를 그대로 따른다:

| 도메인 | 엔티티 (V1 테이블) | 비고 |
| --- | --- | --- |
| inbound (P1) | Product · 추후 KoreanNetMaster, Category, CategoryAttributeMap, MeasurementSession, MeasurementImage | Product는 P3가 읽기 컬럼+재고 캐시만 매핑한 축소판 — P1이 확장·인수 |
| outbound (P2) | Shipment, ShipmentItem, Tote, ToteAssignment, BoxType | 생성은 P3 import가 하지만 소유·확장은 P2 |
| orders (P3) | Order(orders), OrderItem, Region, Line | |
| inventory (P3) | InventoryTx | 재고 증감·조회는 InventoryService 창구로만 |
src/main/resources/db/migration/
  V1__schema.sql    ERD v0.3 전체 16 테이블
  V2__seed.sql      Phase 1 seed — category, region·line 3개, box_type A~E호, tote, 시연 상품 무게 매핑 (D-10)
```

- 스키마 변경은 Flyway 마이그레이션 추가로만 하고, P3 리뷰 후 병합한다 (docs/05 §3).
- 적용된 마이그레이션 파일을 수정했다면 `docker compose down -v`로 DB를 초기화해야 한다 (Flyway 체크섬 검증).

## RDS 로 붙이기 (EC2)

`docker-compose.yml`은 backend만 정의하고, 로컬 db는 `docker-compose.override.yml`에 있다(compose가 자동으로 합침). RDS를 쓰는 서버는 `.env`에 아래를 두면 override가 빠지고 backend가 RDS로 붙는다. 배포 스크립트 명령(`docker compose up -d --build`)은 그대로다.

```
COMPOSE_FILE=docker-compose.yml
POSTGRES_HOST=<RDS 엔드포인트>
POSTGRES_USER=postgres
POSTGRES_PASSWORD=<마스터 비밀번호>
POSTGRES_DB=postgres
```

Flyway가 첫 기동에서 V1~V4를 RDS에 적용한다. RDS 보안 그룹은 5432를 EC2 보안 그룹에서만 허용한다.

## 도메인 커스텀 에러 던지기

```java
throw new ApiException(ErrorCode.OUT_OF_STOCK, "재고가 부족합니다.",
        Map.of("productId", productId));
```

응답: `{ "code": "OUT_OF_STOCK", "message": "재고가 부족합니다.", "detail": { "productId": 1 } }`
