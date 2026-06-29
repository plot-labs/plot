# Plot

[English](README.md) | [한국어](README.ko.md)

**Know what to say next.**

Plot은 크리에이터와 마케팅팀을 위한 AI CMO입니다. 핵심 역할은 단 하나, 다음에 무엇을 말해야 하는지 판단하는 것입니다. Plot은 연결하거나, 업로드하거나, 붙여넣거나, 포워딩한 모든 입력을 **Writing Block**으로 바꾸고, 그 블록에서 유용한 signal을 감지해 source-backed brief, angle, content pack, follow-up으로 이어줍니다.

Plot은 AI writer가 아닙니다. Plot은 connected writing intelligence system입니다.

## 제품 아이디어

크리에이터와 마케팅팀은 이미 좋은 글의 재료를 가지고 있습니다.

- 저장한 링크
- 제품 문서
- GitHub PR
- launch spec
- 고객 이메일
- Slack decision
- RSS article
- 과거 글
- audience reply
- 업로드한 PDF와 메모

문제는 빈 화면이 아닙니다. 문제는 맥락이 흩어져 있다는 것입니다.

Plot은 이 맥락을 Writing Block으로 정규화하고, signal을 추출한 뒤, weekly 또는 launch-specific writing plan으로 바꿉니다.

```txt
Inputs
  -> Writing Blocks
  -> Signals
  -> Brief
  -> Angle
  -> Content Pack
  -> Learn
  -> Follow-up
```

## 핵심 객체

Plot의 핵심 객체는 **Writing Block**입니다.

Writing Block은 Plot이 글쓰기와 narrative 판단에 사용할 수 있는 정규화된 context unit입니다.

예시:

- GitHub PR block
- Slack thread block
- PDF block
- Google Doc block
- RSS article block
- audience comment block
- past post block
- customer email block
- manual note block

Connection, upload, paste, URL, email forward는 모두 input method입니다. 이 모든 입력은 Writing Block을 만듭니다.

## 제품 원칙

**Every input becomes a block.**  
Plot은 upload, connection, URL, paste를 서로 다른 제품 세계로 나누지 않습니다. 모든 입력은 Writing Block이 됩니다.

**Connections are live block factories.**  
연결된 source는 시간이 지나면서 계속 새로운 Writing Block을 만듭니다. 이 block들은 autonomous signal을 발생시킬 수 있습니다.

**The Brief is the home screen.**  
Plot은 blank composer에서 시작하면 안 됩니다. 핵심 경험은 "지금 다음에 말해야 할 것"을 먼저 보여주는 것입니다.

**Source-backed by default.**  
Plot은 그럴듯한 글만 생성하면 안 됩니다. 중요한 claim은 가능한 한 Writing Block과 Signal에 연결되어야 합니다.

## Origin

Plot은 [Tyquill](https://github.com/tyquill)에서 전략적으로 피봇한 제품입니다.

Tyquill은 source를 수집하고 draft를 생성하는 데 집중했습니다. Plot은 그 workflow의 유용한 insight는 유지하되, 제품의 중심을 AI writing assistance에서 connected writing intelligence로 바꿉니다.

핵심 변화는 다음과 같습니다.

```txt
Tyquill: save sources -> generate drafts
Plot: inputs -> Writing Blocks -> Signals -> Brief -> Angle -> Content Pack -> Learn -> Follow-up
```

Plot은 새 제품으로 처음부터 다시 만들어지고 있습니다. Writing Block, source-backed claim, brief, angle, dynamic agent workflow를 중심으로 더 명확한 domain model을 갖습니다.

## 타깃 사용자

초기 타깃:

- founder-led creator
- technical creator
- B2B SaaS founder
- AI/devtool founder
- newsletter와 LinkedIn 운영자

확장 타깃:

- product marketer
- DevRel team
- B2B launch team
- founder-led marketing team

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
