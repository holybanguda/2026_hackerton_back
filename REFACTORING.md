# 리팩터링 내역

이 문서는 기능과 API 계약을 유지하면서 수행한 코드 구조 개선 내역을 기록합니다.

## 2026-09-04: Spring Boot 코드 1차 정리

### 목표

- 추천 연동 코드의 중복과 타입 안정성 문제 완화
- 반복적으로 생성되던 HTTP·JSON 객체 재사용
- 서비스 계층의 DTO 변환 책임을 명확한 메서드로 분리
- 중복 선언과 임시 예외 출력을 정식 로깅으로 교체

이번 작업에는 추천 알고리즘, API 경로와 필드, 데이터베이스 스키마, fallback 정책 변경이 포함되지 않습니다.

### 파일별 변경 사항

#### `ai/AiService.java`

- OpenAI와 FastAPI 기본 주소를 상수로 분리했습니다.
- 추천 호출에 사용하던 `RestTemplate`을 제거하고 기존 `RestClient`로 통일했습니다.
- 매 요청마다 생성하던 `ObjectMapper`를 서비스 인스턴스에서 재사용하도록 변경했습니다.
- FastAPI 응답을 raw `Map`이 아닌 `Map<String, Object>`로 받도록 변경했습니다.
- 추천과 재추천에서 중복되던 응답 조립 코드를 `toRecommendationResponse`로 통합했습니다.
- 외부 응답의 목록, 숫자, 문자열 변환을 타입별 보조 메서드로 분리했습니다.
- URL 메뉴 응답을 읽을 때도 동일한 `ObjectMapper`를 사용하도록 정리했습니다.

#### `recommendation/RecommendationService.java`

- 최초 추천 요청 조건을 응답에 복사하는 코드를 `copyRequestConditions`로 분리했습니다.
- 추천 상세 조회와 전체 조회에서 중복되던 엔티티→응답 변환을 `toResponse`로 통합했습니다.
- JSON 문자열 목록 역직렬화에 `TypeReference<List<String>>`를 적용해 unchecked 경고를 제거했습니다.

#### `recommendation/RecommendationDto.java`

- Lombok `@Data`에 포함되는 `@Getter`, `@Setter` 중복 선언을 제거했습니다.
- DTO의 필드와 직렬화 형태는 변경하지 않았습니다.

#### `menuscan/MenuScanService.java`

- `printStackTrace()`를 SLF4J 오류 로그로 교체해 요청 URL과 예외 추적 정보를 함께 남기도록 했습니다.
- 조건문과 들여쓰기 형식을 정리했습니다.

### 검증

다음 검증을 수행했습니다.

```text
sh gradlew compileJava
BUILD SUCCESSFUL
```

- Java 컴파일 성공
- 기존 unchecked 컴파일 경고 제거
- `git diff --check` 통과
- Python 코드와 API·DB 계약은 변경하지 않음

전체 테스트는 기존 테스트 환경에 MySQL 연결 설정이 없어 `HackertonApplicationTests.contextLoads()`의 DataSource 생성 단계에서 실패합니다. 이번 리팩터링으로 새로 발생한 컴파일 오류는 없습니다.

### 후속 코드 개선 후보

아래 항목은 기능이나 운영 정책에 영향을 줄 수 있어 이번 작업에서 제외했습니다.

- FastAPI URL과 HTTP timeout을 환경 설정으로 이전
- 외부 API 클라이언트와 fallback 정책을 별도 클래스로 분리
- Bean Validation 및 전역 예외 응답 도입
- 외부 API 호출과 데이터베이스 트랜잭션 범위 분리
- `backend/ai/main.py`의 라우터, 크롤러, OCR, 추천 엔진 모듈화
- 테스트용 데이터베이스 또는 repository mock을 이용한 독립 테스트 환경 구성

