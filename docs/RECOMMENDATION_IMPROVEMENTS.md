# 추천 및 메뉴판 동시 분석 개선 (2026-09-15)

기존에 무엇이 문제였고 왜 이 방식으로 변경했는지는 [개선 전후 정리](IMPROVEMENT_REVIEW.md)를 참고하세요.

## 적용 내용과 주요 파일

- `RecommendationDto`: 공통 Conditions, recommendedItems, 직접 메뉴 재사용을 위한 menuList.
- `RecommendationService`: 조건 병합/공통 응답 조립, 금액 검증, 스냅샷 저장/복원, 조건별 조합 기록.
- `RecommendationEntity`: snapshot, combinationHistory, version 컬럼 추가.
- `AiService`: recommendedItems 전체 역직렬화, 설정 가능한 AI 주소와 연결/읽기 timeout, 명시적 실패 전달.
- `MenuScanService`: URL별 ConcurrentHashMap + CompletableFuture 작업 공유.
- `MenuCacheWriter`: 짧은 저장 트랜잭션. 프록시 반환 뒤에만 공유 future 완료.
- `ApiExceptionHandler`: HTTP 상태와 code/message 오류 구조.
- `backend/ai/main.py`: fallback의 제외 조합/예산 검사 및 대안 소진 오류. 기존 주 추천 점수식 유지.
- 개선 지점에는 “개선” 주석 및 해당 처리의 이유를 기재했다.

기존 API 경로와 recommendedMenus, totalPrice, reason, engineType 등의 필드는 유지한다.
최초 추천은 저장하지 않고 confirm에서 저장한다. 가입·공유 기능은 추가하지 않았다.

## 요청/응답 예시

### 최초 추천

`POST /api/recommendation`

```json
{
  "restaurantUrl": "https://example.com/menu?store=1",
  "peopleCount": 2,
  "budget": 40000,
  "meetingType": "친구 모임",
  "excludedFoods": ["새우"],
  "bigEaterCount": 0,
  "dietCount": 0,
  "spicyLevel": 3,
  "todayPreference": "고기"
}
```

URL이 있으면 캐시 메뉴를 사용한다. URL 없이 직접 menuList를 전달하는 흐름도 유지된다.
신규 추천에 필요한 메뉴가 없으면 422 MENU_REANALYSIS_REQUIRED다.

응답 구조 예시(선택 결과는 AI에 따라 다름):

```json
{
  "recommendationId": null,
  "restaurantUrl": "https://example.com/menu?store=1",
  "menuList": [
    {"menuName": "삼겹살", "price": 15000, "category": "메인"},
    {"menuName": "계란찜", "price": 4000, "category": "사이드"}
  ],
  "peopleCount": 2,
  "budget": 40000,
  "meetingType": "친구 모임",
  "excludedFoods": ["새우"],
  "bigEaterCount": 0,
  "dietCount": 0,
  "spicyLevel": 3,
  "todayPreference": "고기",
  "recommendedItems": [
    {"menuName": "삼겹살", "price": 15000, "count": 1, "totalItemPrice": 15000, "category": "메인", "desc": "대표 메뉴"},
    {"menuName": "계란찜", "price": 4000, "count": 1, "totalItemPrice": 4000, "category": "사이드", "desc": ""}
  ],
  "recommendedMenus": ["삼겹살", "계란찜"],
  "totalPrice": 19000,
  "reason": "추천 사유",
  "engineType": "GPT_CHIEF_CUSTOM_KNAPSACK_SUPPORT"
}
```

- 단가/수량은 양수 정수이며 수량은 최대 10,000이다.
- 항목 합계 = 단가 × 수량, 총액 = 모든 항목 합계여야 한다. 신규 상세 결과는 예산도 초과할 수 없다.
- 정수 범위 초과, 소수 수량/금액, 중복 이름 및 목록/상세 불일치를 거부한다.
- menuList가 있으면 추천 항목의 존재 여부와 단가도 비교한다.
- recommendedMenus는 수량 1이면 이름, 수량 2 이상이면 `이름 x N`이다.
- Python은 현재 서로 다른 메뉴 행 조합을 선택하므로 고유한 메뉴 입력에서는 일반적으로 수량 1이다. 상세 구조는 여러 수량도 처리한다.

### 확정 / 조회

`POST /api/recommendation/confirm`에 최초 추천 응답 전체를 보낸다.
특히 직접 입력 메뉴를 사용했다면 **menuList도 함께 보낸다**. 확정 이후 ID가 채워진다.

`GET /api/recommendation/{id}` 및 `GET /api/recommendation`은 동일한 상세 항목·조건 구조를 반환한다.
조회 시 현재 캐시 가격으로 보정하지 않고 **확정/재추천 저장 당시의 단가·수량·메뉴명**을 반환한다.

이 API는 기존처럼 클라이언트가 전송한 확정 내용을 받는다. 서버는 형식/산술 및 전달된 메뉴판과의 일관성을 검사하며, 최초 응답의 진위 인증/서명 기능은 이번 범위에 없다.

### 부분 재추천

`PUT /api/recommendation/1`

```json
{
  "budget": 50000,
  "excludedFoods": [],
  "todayPreference": ""
}
```

- `{}`만 보내면 기존 조건으로 다른 조합을 요청한다.
- 생략한 조건은 이전 값을 유지한다.
- `excludedFoods: []`는 제외 조건 해제, `todayPreference: ""`는 선호 초기화다.
- 조건의 **명시적 null은 400 INVALID_REQUEST**다. 숫자는 0 등 유효한 초기값을 사용한다.
- 기본 조건: peopleCount=2, budget=60000, meetingType="친구 모임", excludedFoods=[], bigEaterCount=0, dietCount=0, spicyLevel=3, todayPreference="".
- 인원/예산은 양수, 대식가·다이어트 수는 0~인원수, 매운맛은 0~5다.
- 저장된 URL이 있으면 menuList를 보내지 않아도 DB 캐시를 조회한다.
- URL 없는 직접 입력 흐름은 저장된 menuList를 재사용한다. 필요하면 새 목록으로 교체할 수 있다.
- `restaurantUrl: ""`로 URL 연결을 해제할 수 있다. 이때 전달한 menuList 또는 저장된 목록을 사용한다.
- 신규 URL이 있으면 그 URL의 DB 캐시가 우선이다.
- elapsedMinutes를 생략하면 생성 시각 기준으로 계산한다. budgetDelta는 저장 예산과 요청 예산의 차이로 서버가 계산한다. 현재 Python 조합 점수에는 두 값이 사용되지 않는다.
- 기존 ID가 없으면 404를 반환하며 새 추천으로 전환하지 않는다.
- AI 오류/대안 소진/금액 오류/동시 갱신 충돌 시 기존 저장 결과를 유지한다.
- 재추천이 성공하면 해당 이력의 현재 결과 스냅샷을 갱신한다. 과거 조합은 별도로 유지한다.

### 오류 형식

```json
{
  "code": "NO_ALTERNATIVE_COMBINATION",
  "message": "동일 조건에서 새로운 메뉴 조합이 없습니다."
}
```

| HTTP | code | 의미/프론트 처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_CONDITIONS / INVALID_MENU / INVALID_RESULT | 입력을 수정한다. |
| 404 | RECOMMENDATION_NOT_FOUND | 이력이 없거나 만료됐다. |
| 409 | NO_ALTERNATIVE_COMBINATION | 현재 결과를 유지하고 조건 변경 안내. |
| 409 | RECOMMENDATION_CONFLICT | 동시 수정 충돌. 이력을 다시 조회한다. |
| 422 | MENU_REANALYSIS_REQUIRED | 메뉴를 다시 스캔하거나 직접 메뉴 목록을 전달한다. |
| 422 | MENU_ANALYSIS_EMPTY | 메뉴를 추출하지 못했다. 다른 URL/메뉴판으로 시도한다. |
| 502 | AI_REQUEST_FAILED / INVALID_AI_RESULT / INVALID_RESULT | AI 오류 또는 잘못된 결과. 기존 추천을 유지한다. |
| 502 | MENU_ANALYSIS_FAILED / INVALID_MENU_ANALYSIS | 메뉴 분석·저장 실패. 재시도 가능. |
| 504 | MENU_WAIT_TIMEOUT | 해당 HTTP 요청의 대기만 종료. 공유 작업은 계속 진행된다. |
| 503 | MENU_WAIT_INTERRUPTED / MENU_ANALYSIS_UNAVAILABLE | 요청 중단 또는 서버 종료 중 작업 등록 실패. |

## 동일 조합 방지 범위

같은 추천 ID 안에서 **메뉴판 + 조건**을 키로 사용한다.

- 메뉴판: 공백 정리한 URL과 메뉴명·단가·카테고리 목록. 메뉴 순서는 무시한다.
- 조건: 인원수, 예산, 모임 유형, 제외 음식, 대식가/다이어트 수, 매운맛, 선호.
- 제외 음식 순서와 중복은 무시한다.
- profile, elapsedMinutes, budgetDelta는 범위에 포함하지 않는다.
- 조건을 변경하면 별도의 기록을 사용한다. 다시 이전 조건으로 돌아오면 이전 기록도 재사용한다.
- 조합은 **메뉴명을 수량만큼 반복한 뒤 정렬**한다. 예: A 2개+B 1개 → `["A","A","B"]`.
- 순서만 다른 조합은 같고, 수량이 다르면 다른 조합이다.
- Python excludedCombos로 전달하고 Spring에서도 결과를 재검사한다.
- fallback도 제외 조건과 예산을 준수한다. 탐색 범위에서 대안이 없으면 409를 반환한다.
- 기존 엔진의 최대 16개 후보 제한은 유지된다. “대안 없음”은 전체 메뉴의 수학적 전수 탐색 증명이 아니다.

구버전 이력은 전체 메뉴판/수량 정보가 없을 수 있다. URL이 있는 이력의 첫 재추천은 현재 캐시를 기준으로 범위를 시작하며, 당시 이름 목록을 초기 제외 조합으로 사용한다. 알 수 없는 상세 단가/수량은 이력 응답에 생성하지 않는다.

## DB 변경 및 기존 데이터

`recommendations`에 다음 nullable 스냅샷 컬럼 및 버전 컬럼을 추가한다.

| 컬럼 | 타입 | 의미 |
| --- | --- | --- |
| snapshot | LONGTEXT NULL | 상세 결과 + 전체 조건 + 원본 menuList의 JSON |
| combination_history | LONGTEXT NULL | 메뉴판/조건별 과거 조합 JSON |
| version | BIGINT NOT NULL DEFAULT 0 | JPA 낙관적 잠금 버전 |

- 기존 컬럼과 menu 테이블은 유지한다.
- snapshot이 없는 행은 기존 컬럼으로 조회하고 recommendedItems=[]를 반환한다.
- legacy 확정 요청도 이름 목록/총액만으로 저장할 수 있다. 상세 금액 검증이 불가능하므로 이름/총액의 기본 유효성만 검사한다.
- 직접 메뉴 흐름에서 menuList 없이 확정한 구버전 이력은 목록이 없으므로 재추천 시 422를 반환한다.
- 상세 정보는 현재 가격에서 역산하지 않는다.
- 스키마 변경 SQL: [20260915_recommendation_snapshot.sql](../backend/back/hackerton/db/20260915_recommendation_snapshot.sql).
- SQL은 수동 1회 적용용이며 자동 실행되지 않는다. 아직 컬럼이 없는 DB에 적용한다. 이미 ddl-auto=update로 생성됐다면 다시 실행하지 않는다.
- 현재 프로젝트의 ddl-auto=update도 개발 환경에서 컬럼을 추가한다. 운영 반영 전 대상 스키마를 확인하고 컬럼 추가를 먼저 적용한다.
- H2(MySQL 모드) 저장/복원은 검증했으며 실제 운영 MySQL에 연결하거나 DDL을 실행하지 않았다.

## 동일 URL 요청 병합

1. 캐시 조회 → 있으면 즉시 반환.
2. 없으면 trim한 URL로 putIfAbsent를 사용해 future 소유권 확보.
3. 소유자가 별도 가상 스레드에서 캐시 재확인 후 AI 호출.
4. 정상 비어 있지 않은 메뉴만 짧은 별도 트랜잭션으로 저장.
5. 커밋 후 future를 완료하여 모든 대기 요청에 결과 공유.
6. 성공/실패 모두 remove(url, 해당 future)로 정리.

요청과 공유 작업은 분리돼 있어 최초 요청이나 대기 요청의 timeout/연결 종료가 공유 작업을 취소하지 않는다.
다른 URL은 별도 작업이다. 전역 잠금과 Redis는 사용하지 않는다.

설정:

```properties
ai.server.url=${AI_SERVER_URL:https://2026hackertonai-production.up.railway.app}
ai.connect-timeout-ms=5000
ai.read-timeout-ms=60000
menu.scan.wait-timeout-ms=75000
```

- AI 연결/읽기 timeout은 외부 호출을 제한하고, wait-timeout은 각 HTTP 요청의 대기만 제한한다.
- 캐시 저장 트랜잭션 timeout은 10초다.
- 실패 결과와 빈 결과는 캐시에 저장하지 않는다. 커밋 실패도 대기 요청에 오류로 전달된다.
- 앞뒤 공백만 제거한다. 쿼리 파라미터, 경로, URL 대소문자는 보존한다.
- **단일 Spring 인스턴스 내부에서만 병합한다.** 다중 인스턴스는 각 서버가 같은 URL을 분석·저장할 수 있다.
- 메뉴 캐시 만료/강제 재분석 API는 이번 범위에 추가하지 않았다.
- 작업/대기자는 프로세스 메모리에 있으므로 서버 재시작 시 유지되지 않는다.

## 프론트 반영 사항

1. 표시에는 recommendedItems의 menuName/price/count/totalItemPrice/category를 사용한다.
2. recommendedItems가 비어 있는 과거 이력은 recommendedMenus와 기존 totalPrice만 표시한다. 단가·수량은 미상으로 처리한다.
3. confirm에는 응답의 상세 항목, 조건, menuList까지 전달한다. 특히 직접 입력 메뉴 목록을 누락하지 않는다.
4. 재추천은 ID와 변경할 조건만 보낸다. 불필요한 null은 제거하고 []/""로 초기화한다.
5. 409/502에서 현재 화면의 추천을 삭제하거나 새 결과로 교체하지 않는다.
6. 504는 해당 요청 대기 종료이며 분석은 진행 중일 수 있다. 잠시 후 같은 URL로 조회하면 작업에 합류하거나 캐시를 받는다.
7. AI_SERVER_URL로 로컬 AI 서버를 연결할 수 있다. 프론트의 기존 Spring API 경로는 바뀌지 않는다.

## 검증

2026-09-15 최종 결과: Java 테스트 24개, Python 테스트 7개 모두 통과. Git diff 공백 검사도 통과했다.

```powershell
cd backend/back/hackerton
.\gradlew.bat test
```

저장소 루트에서:

```powershell
python -B -m unittest discover -s backend/ai/tests -v
```

- Spring 단위 테스트: 조건 보존, 상세 스냅샷, legacy 호환, 직접 메뉴 재사용, 조합 기록과 조건 복원, 실패 시 보존, 유효성 검사.
- 동시성 테스트: 동일 URL 10개 요청의 1회 분석/저장, 다른 URL 독립 실행, 캐시 및 재확인, 실패 공유·재시도, 개별 timeout, 저장 실패.
- HTTP stub: AI 상세 응답 파싱, 대안 소진 전달, 소수 수량 거부.
- H2 통합 테스트: 커밋 후 조회, 실제 추천 저장/갱신/오류 후 유지, version 증가.
- Python 7개 테스트: 실제 엔진 함수를 AST로 로딩해 OCR/LLM 초기화를 격리하고 fallback·예산·제외 조합·수량 표현을 검증.
- 외부 AI/실제 크롤링은 호출하지 않았다.
