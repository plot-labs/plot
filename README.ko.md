<div align="center">
  <img src="apps/web/public/plot-icon.svg" alt="Plot logo" width="88" />
  <h1>Plot</h1>
  <p><strong>빠르게 출시하고, 적게 쓰세요.</strong></p>
  <p>병합된 제품 작업을 편집 가능하고 출처가 표시된 changelog로 만듭니다.</p>
</div>

[English](README.md) | [한국어](README.ko.md)

## 현재 제품

Plot은 현재 하나의 완결된 흐름을 지원합니다.

```txt
GitHub 저장소 하나 연결
  -> 병합된 pull request 가져오기
  -> Plot에 changelog 요청
  -> draft 옆에서 citation 확인
  -> 편집 후 Markdown 복사 또는 다운로드
```

게시 여부와 게시된 내용은 Plot 밖에서 사용자가 직접 관리합니다.

## 저장소

```txt
apps/
  web/  Next.js 애플리케이션과 same-origin API proxy
  api/  Kotlin Spring Boot API와 generation worker

packages/
  auth/        Better Auth 설정과 allowlist policy
  api-client/  Plot API용 typed browser client
```

PostgreSQL이 system of record이며,
`apps/api/src/main/resources/db/migration`의 Flyway migration이 스키마의
기준입니다.

## 개발

필요 환경:

- Java 21
- Node.js와 pnpm
- Docker
- `just`

```bash
pnpm install
just dev-api
just dev-web
```

검증:

```bash
just lint
just test
just build
```

API integration test는 Testcontainers로 PostgreSQL을 실행합니다.

## 운영 문서

- [GitHub App 개발 smoke test](docs/operations/github-app-development-smoke-test.md)
- [Polar subscription webhook](docs/operations/polar-subscription-webhook.md)
