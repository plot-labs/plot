# Plot API

Kotlin / Spring Boot 백엔드입니다. 기능별 코드는 `src/main/kotlin/com/plot/api`에 있습니다.

## 코드를 읽는 순서

### 채팅 요청과 재시도

```text
chat/ChatController
  ├─ POST → ChatRunService → AgentRunQueryPersistence / SQL → AgentRunDispatcher
  └─ GET  → ChatQueryService → AgentRunQueryPersistence → 응답 DTO
```

- `ChatRunService`: 새 실행, 자동 실행, 다른 콘텐츠로 변환, 재시도를 등록합니다. 저장이 커밋된 뒤 작업자를 깨웁니다.
- `ChatQueryService`: 실행·대화·응답 버전을 조회하고 응답 DTO를 만듭니다. 재시도 가능 여부를 읽지만, 실제 재시도는 `ChatRunService`가 잠금 안에서 다시 검증합니다.
- `ChatCompatibilityWriter`: 기존 실행과 채팅의 turn/version/envelope를 연결합니다. 대화 조회 시 기존 데이터의 누락된 연결을 채우는 동작도 유지합니다.
- `chat/dto`: HTTP 요청·응답 형식입니다. 폴더 이름과 별개로 기존 `/api/agent-runs` 및 `/api/sessions` 주소를 사용합니다.

### 백그라운드 실행과 결과물

```text
routine/AgentRunDispatcher → AgentRunWorker
  → 읽기 도구 또는 artifact/workflow
  → ArtifactWorkflowRunWorker → 모델 호출 → 결과 저장
  → artifact/ArtifactController에서 조회·수정·내보내기
```

`routine`에는 예약 실행과 공통 AgentRun 작업자가 있습니다. 채팅과 예약 실행이 같은 작업자를 사용합니다.

## 저장소와 설정

- 기능별 `*Repository` / `*Persistence`에서 DB 접근을 찾습니다. `persistence/JooqSqlExecutor`는 공통 SQL 실행과 예외 변환을 담당합니다.
- `JooqTransactionExecutor` 호출 블록은 여러 SQL 작업을 하나로 묶는 경계입니다. 특히 재시도의 잠금·복사·등록 순서를 함께 읽어야 합니다.
- 일반 서비스·저장소는 생성자 주입을 사용합니다. `ArtifactWorkflowConfiguration`에는 작업자 옵션, 실행기, 재시도, 종료 정책과 함수 인자 조립만 남겨둡니다.
- `src/main/resources/db/migration`은 기존 데이터의 스키마 이력입니다. 코드 패키지 이동은 테이블이나 HTTP 계약 변경을 의미하지 않습니다.

## 로컬 검증

Java 21 도구 체인과 실행 중인 Docker가 필요합니다. API 폴더에서 실행합니다.

```sh
./gradlew test
./gradlew build
```

기본 테스트는 Testcontainers의 PostgreSQL을 사용하고 `live-eval` 태그는 제외합니다. 실제 모델 평가는 별도 `liveEval` 작업입니다.
