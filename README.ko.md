<div align="center">
  <img src="apps/web/public/plot-icon.svg" alt="Plot logo" width="88" />
  <h1>Plot</h1>
  <p><strong>빠르게 출시하고, 적게 쓰세요.</strong></p>
  <p>출시된 작업을 검증 가능하고 출처가 표시된 고객용 콘텐츠로 만듭니다.</p>
</div>

[English](README.md) | [한국어](README.ko.md)

## 제품

에이전트 시대에 출시는 빨라졌습니다. 하지만 고객에게 도달하는 콘텐츠는 여전히 느립니다. Plot은 이 격차를 메우기 위해 존재합니다.

**Changelog는 첫 번째 쐐기입니다.** 현재 제품은 출시된 GitHub release를 출처가 표시된 changelog draft로 만들고, 운영자가 검토하여 게시합니다. 동일한 신뢰 루프 — 정확한 출처 범위, 검증 가능한 인용, 휴먼 승인 게시, 절대 자동 게시 금지 — 는 미래 콘텐츠 표면의 토대입니다.

Plot은 다음 다섯 제품 표면을 함께 제공합니다.

- **Chat** — 출처를 기반으로 하는 대화형 AgentRun 작업
- **Routines** — 예약 또는 수동으로 시작하는 AgentRun 작업
- **Artifacts** — 편집·리비전·출처 인용이 가능한 문서와 Markdown export
- **GitHub release automation** — release webhook, 신뢰 가능한 범위 계산, draft 생성
- **Billing/entitlement** — 구독, trial, plan, workspace 접근 정책

GitHub 연결, 저장소 범위, writing block은 **Integrations**(워크스페이스 설정)에서 구성하고 Chat, Routines, Artifacts에서 사용합니다.

[디자인 명세](DESIGN.md)는 구현 가능한 헌법입니다: 7가지 법칙, 제품 표면, 보이스 레지스터,
시각 시스템, 컴포넌트 규칙, 화면 목록. [시스템 아키텍처 개요](docs/architecture/system-overview.md)에서
요청 경계, 실행 소유권, persistence 규칙, 검증 명령을 설명합니다. 게시 여부는 계속
Plot 밖에서 사용자가 직접 관리합니다.

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
- Bun
- Docker
- `just`

```bash
bun install
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
