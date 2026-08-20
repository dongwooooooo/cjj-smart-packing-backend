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
docker compose up --build   # db + backend
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
src/main/resources/db/migration/
  V1__schema.sql    ERD v0.3 전체 16 테이블
  V2__seed.sql      Phase 1 seed — category, region·line 3개, box_type A~E호, tote, 시연 상품 무게 매핑 (D-10)
```

- 스키마 변경은 Flyway 마이그레이션 추가로만 하고, P3 리뷰 후 병합한다 (docs/05 §3).
- 적용된 마이그레이션 파일을 수정했다면 `docker compose down -v`로 DB를 초기화해야 한다 (Flyway 체크섬 검증).

## 도메인 커스텀 에러 던지기

```java
throw new ApiException(ErrorCode.OUT_OF_STOCK, "재고가 부족합니다.",
        Map.of("productId", productId));
```

응답: `{ "code": "OUT_OF_STOCK", "message": "재고가 부족합니다.", "detail": { "productId": 1 } }`
