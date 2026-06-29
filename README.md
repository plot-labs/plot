# Plot

[English](README.md) | [한국어](README.ko.md)

**Know what to say next.**

Plot is an AI CMO for creators and marketing teams focused on one job: knowing what to say next. It turns connected, uploaded, pasted, or forwarded inputs into **Writing Blocks**, detects useful signals from those blocks, and helps users decide what to say next through source-backed briefs, angles, content packs, and follow-ups.

Plot is not an AI writer. It is a connected writing intelligence system.

## Product Idea

Creators and marketing teams already have the raw material for strong writing:

- saved links
- product docs
- GitHub PRs
- launch specs
- customer emails
- Slack decisions
- RSS articles
- past posts
- audience replies
- uploaded PDFs and notes

The problem is not a blank page. The problem is that the context is scattered.

Plot normalizes that context into Writing Blocks, extracts signals, and turns those signals into a weekly or launch-specific writing plan.

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

## Core Object

The core object in Plot is the **Writing Block**.

A Writing Block is a normalized unit of context that Plot can use for writing and narrative decisions.

Examples:

- GitHub PR block
- Slack thread block
- PDF block
- Google Doc block
- RSS article block
- audience comment block
- past post block
- customer email block
- manual note block

Connections, uploads, paste, URLs, and email forwards are all input methods. They all produce Writing Blocks.

## Product Principles

**Every input becomes a block.**  
Plot does not treat uploads, connections, URLs, and paste as separate product worlds. They all become Writing Blocks.

**Connections are live block factories.**  
A connected source keeps producing new Writing Blocks over time. Those blocks can trigger autonomous signals.

**The Brief is the home screen.**  
Plot should not start from a blank composer. The primary experience is: "This is what you should talk about next."

**Source-backed by default.**  
Plot should not only generate plausible writing. Important claims should map back to Writing Blocks and Signals.

## Origin

Plot started as a strategic pivot from [Tyquill](https://github.com/tyquill).

Tyquill focused on collecting sources and generating drafts. Plot keeps the useful insight behind that workflow, but changes the center of the product: from AI writing assistance to connected writing intelligence.

The core shift is:

```txt
Tyquill: save sources -> generate drafts
Plot: inputs -> Writing Blocks -> Signals -> Brief -> Angle -> Content Pack -> Learn -> Follow-up
```

Plot is being built from scratch as a new product, with a clearer domain model around Writing Blocks, source-backed claims, briefs, angles, and dynamic agent workflows.

## Target Users

Initial:

- founder-led creators
- technical creators
- B2B SaaS founders
- AI/devtool founders
- newsletter and LinkedIn operators

Expansion:

- product marketers
- DevRel teams
- B2B launch teams
- founder-led marketing teams

## Architecture

Plot is a monorepo with a generated Next.js frontend and a generated Spring Boot backend.

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

The backend and agent runtime intentionally live in one Spring Boot process for v1. The goal is a modular monolith that keeps domain state, jobs, policy checks, and agent trace in one place.

## Commands

```bash
pnpm dev:web
pnpm build:web
pnpm lint:web

pnpm dev:api
pnpm build:api
pnpm test:api
```

## Docs

- [Project Structure](docs/architecture/project-structure.md)
- [ADR 0001: Monorepo With Generated Apps](docs/decisions/0001-monorepo-generated-apps.md)
