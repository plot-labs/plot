# Plot

[English](README.md) | [한국어](README.ko.md)

**Keep docs and content in sync with shipping.**

AI coding은 product/engineering team의 shipping 속도를 빠르게 만들고 있지만, docs, release note, changelog, launch brief, customer-facing content는 여전히 예전 속도로 움직입니다. Plot은 연결하거나, 업로드하거나, 붙여넣거나, 포워딩한 모든 입력을 **Source Block**으로 바꾸고, 그 block에서 유용한 product signal을 감지해 brand voice에 맞는 source-backed docs와 release content를 생성합니다.

Plot은 범용 AI writer가 아닙니다. Plot은 AI coding으로 빨라진 product shipping을 따라잡기 위한 connected documentation and release-content system입니다.

## 제품 아이디어

Product/engineering team은 이미 좋은 문서와 release content의 재료를 가지고 있습니다. 다만 AI coding 때문에 그 재료가 content process보다 더 빠르게 움직입니다.

- PRD와 product spec
- RFC와 design doc
- GitHub PR과 issue
- Linear 또는 Jira ticket
- launch spec
- 고객 이메일과 support thread
- Slack decision
- incident note
- 업로드한 PDF와 research note

문제는 빈 화면이 아닙니다. 문제는 맥락이 흩어져 있다는 것입니다.

Plot은 이 맥락을 Source Block으로 정규화하고, change signal을 추출한 뒤, weekly 또는 launch-specific docs/content catch-up plan으로 바꿉니다.

```txt
Inputs
  -> Source Blocks
  -> Signals
  -> Brief
  -> Catch-up Plan
  -> Release Pack
  -> Review
  -> Follow-up Update
```

## 핵심 객체

Plot의 핵심 객체는 **Source Block**입니다.

Source Block은 Plot이 docs, release content, brand voice adaptation, claim 판단에 사용할 수 있는 정규화된 product/engineering context unit입니다.

예시:

- GitHub PR block
- Linear issue block
- Slack thread block
- PDF block
- Google Doc block
- RFC block
- incident note block
- support thread block
- customer email block
- manual note block

Connection, upload, paste, URL, email forward는 모두 input method입니다. 이 모든 입력은 Source Block을 만듭니다.

## 제품 원칙

**Every input becomes a block.**  
Plot은 upload, connection, URL, paste를 서로 다른 제품 세계로 나누지 않습니다. 모든 입력은 Source Block이 됩니다.

**Connections are live block factories.**  
연결된 source는 시간이 지나면서 계속 새로운 Source Block을 만듭니다. 이 block들은 autonomous product/docs signal을 발생시킬 수 있습니다.

**The Catch-up Brief is the home screen.**  
Plot은 blank composer에서 시작하면 안 됩니다. 핵심 경험은 "무엇이 ship됐고, 어떤 source가 이를 뒷받침하며, 어떤 docs/content가 뒤처졌고, brand voice로 어떻게 고쳐야 하는지"를 먼저 보여주는 것입니다.

**Source-backed by default.**  
Plot은 그럴듯한 copy만 생성하면 안 됩니다. 중요한 product claim은 가능한 한 Source Block과 Signal에 연결되어야 합니다.

## Origin

Plot은 [Tyquill](https://github.com/tyquill)에서 전략적으로 피봇한 제품입니다.

Tyquill은 source를 수집하고 draft를 생성하는 데 집중했습니다. Plot은 그 workflow의 유용한 insight는 유지하되, 제품의 중심을 broad AI writing assistance에서 product team을 위한 connected documentation intelligence로 바꿉니다.

핵심 변화는 다음과 같습니다.

```txt
Tyquill: save sources -> generate drafts
Plot: inputs -> Source Blocks -> Signals -> Catch-up Brief -> Release Pack -> Review -> Follow-up Update
```

Plot은 새 제품으로 처음부터 다시 만들어지고 있습니다. Source Block, source-backed product claim, brand voice, catch-up brief, release pack, dynamic agent workflow를 중심으로 더 명확한 domain model을 갖습니다.

## 타깃 사용자

초기 타깃:

- product-led founder
- 작은 product/engineering team
- AI/devtool team
- DevRel 및 developer experience team
- product change를 자주 shipping하는 team

확장 타깃:

- product marketer
- DevRel team
- B2B launch team
- solutions 및 sales engineering team

## 아키텍처

Plot은 생성기로 만든 Next.js frontend와 Spring Boot backend를 포함하는 monorepo입니다.

```txt
apps/
  web/  Next.js app
  api/  Spring Boot API and dynamic loop agent runtime

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

v1에서는 backend와 agent runtime을 하나의 Spring Boot 프로세스 안에 둡니다. 목표는 domain state, job, policy check, agent trace를 한 곳에서 관리하는 modular monolith입니다.

## 명령어

```bash
pnpm dev:web
pnpm build:web
pnpm lint:web

pnpm dev:api
pnpm build:api
pnpm test:api
```

## 문서

- [Project Structure](docs/architecture/project-structure.md)
- [ADR 0001: Monorepo With Generated Apps](docs/decisions/0001-monorepo-generated-apps.md)
