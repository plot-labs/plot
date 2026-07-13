<div align="center">
  <img src="apps/web/public/plot-icon.svg" alt="Plot logo" width="88" />
  <h1>Plot</h1>
  <p><strong>Ship fast. Write less. Stay in your team's voice.</strong></p>
  <p>
    AI-assisted shipping 속도를 따라가지 못하는 docs, release note, changelog,
    customer update, launch content를 위한 autonomous post-shipping update
    workspace입니다.
  </p>
  <p>
    <a href="docs/product/product-definition.md"><strong>Product Definition</strong></a>
    ·
    <a href="docs/product/agentic-ux.md"><strong>Agentic UX</strong></a>
    ·
    <a href="docs/architecture/data-architecture.md"><strong>Data Architecture</strong></a>
  </p>
  <p>
    <img alt="Status: in development" src="https://img.shields.io/badge/status-in_development-030303?style=flat-square">
    <img alt="Sources: connected" src="https://img.shields.io/badge/sources-connected-030303?style=flat-square">
    <img alt="Publishing: human controlled" src="https://img.shields.io/badge/publishing-human_controlled-030303?style=flat-square">
  </p>
</div>

[English](README.md) | [한국어](README.ko.md)

## Plot은 무엇인가?

AI coding agent는 product/engineering team의 shipping 속도를 빠르게 만들고
있지만, shipped work 이후의 글쓰기 작업은 여전히 예전 속도로 움직입니다.

Plot은 실제로 ship된 작업에서 시작해 connected source record를 **Writing
Block**으로 바꾸고, 팀의 voice에 맞는 source-backed update pack을
준비합니다. 제품 표면은 source 연결 화면이 아니라 source-backed update
workspace입니다: sessions, sources, packs, voice, source citations.

Plot은 범용 AI writer도, company knowledge layer도 아닙니다. Agent가 작업을
준비하고, 사람은 Plot 밖에서 복사, 수정, publish를 통제합니다.

## Product Loop

```txt
Ask for an update
  -> choose shipping window and sources
  -> inspect Writing Blocks
  -> apply template and voice
  -> generate an update pack
  -> inspect source citations and style
  -> copy, edit, or publish outside Plot
```

## Plot이 하는 일

- 빈 화면이 아니라 shipped product work에서 update pack을 만듭니다.
- 생성된 content를 그것을 뒷받침하는 source block에 연결합니다.
- changelog, docs update, customer update, launch draft, social copy 같은
  channel variant를 만듭니다.
- example, rule, template, visible source citation을 통해 voice/style을
  유지합니다.
- Publishing은 Plot 밖에서 사람이 통제합니다.

## Product Surface

| Surface | Role |
| --- | --- |
| Sessions | 사람이나 agent가 update pack을 요청하고 진행 상황을 따라가는 chat-like work session입니다. |
| Sources | Shipped-work timeline, source selection, import, coverage를 다룹니다. |
| Packs | Draft update pack, channel variant, source citation을 봅니다. |
| Voice | Team voice, channel style, sample, explicit rule을 관리합니다. |
| Settings | Workspace, member, permission, source connection을 관리합니다. |

## How It Works

- Shipped-work source를 연결하고 update가 필요한 release window를 선택.
- PR, issue, commit, tag, release, comparison range를 위한 Writing Block.
- 초기 생성은 direct model-provider API call로 시작.
- 반복 가능한 출력을 위한 Content Template과 Voice Profile.
- 생성 content 옆에 source citation과 style guidance 표시.
- Publishing은 Plot 밖에서 사람이 통제.

## Architecture

Plot은 생성기로 만든 Next.js frontend와 backend service를 포함하는
monorepo입니다. Domain model은 production backend를 비용, 배포 방식,
운영 부담에 맞춰 선택할 수 있도록 framework-neutral하게 둡니다.

```txt
apps/
  web/  Next.js app
  api/  Kotlin Spring Boot backend and agent runtime

packages/
  api-client/  generated TypeScript client later
  ui/          shared UI components later
  config/      shared frontend config later

infra/
  docker/      local development services
  storage/     object storage notes/config

docs/
  product/       PRD and product notes
  architecture/  system and module docs
  decisions/     architecture decision records
  api/           API notes
  operations/    local/dev/ops runbooks
```

Core update loop를 검증하는 동안 backend는 하나의 service로 둡니다. 초기 AI
생성은 그 service가 관리하는 direct model-provider API call로 시작하고, 더
깊은 orchestration, authenticated integration, automation은 core update
loop가 동작한 뒤 추가합니다.

## Development

`just`를 root command runner로 사용합니다. `pnpm`은 JavaScript package manager와
workspace manager 역할로 유지합니다.

```bash
brew install just

just dev-api
just dev-web
just test
just build
just lint
```

기존 `pnpm dev:api`, `pnpm build:web` 같은 script는 호환용 wrapper로 유지합니다.
GitHub App adapter의 local 검증 절차는
[`docs/operations/github-app-development-smoke-test.md`](docs/operations/github-app-development-smoke-test.md)에
정리되어 있습니다.
API 테스트는 기본적으로 Testcontainers를 사용하며, Docker를 사용할 수 없는
환경에서는 `PLOT_TESTCONTAINERS_ENABLED=false`, `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`으로 임시 로컬 PostgreSQL을 지정할 수 있습니다.

## Docs

- [Product Definition](docs/product/product-definition.md)
- [Agentic UX Direction](docs/product/agentic-ux.md)
- [Project Structure](docs/architecture/project-structure.md)
- [Data Architecture](docs/architecture/data-architecture.md)
- [Data ERD](docs/architecture/data-erd.mmd)
- [GitHub App Development Smoke Test](docs/operations/github-app-development-smoke-test.md)
- [ADR 0001: Monorepo With Generated Apps](docs/decisions/0001-monorepo-generated-apps.md)
