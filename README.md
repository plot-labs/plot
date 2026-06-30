# Plot

[English](README.md) | [한국어](README.ko.md)

**Keep docs and content in sync with shipping.**

AI coding is making product and engineering teams ship faster, but docs, release notes, changelogs, launch briefs, and customer-facing content still move at the old speed. Plot turns connected, uploaded, pasted, or forwarded inputs into **Source Blocks**, detects useful product signals from those blocks, and drafts source-backed docs or release content in the team's brand voice.

Plot is not a generic AI writer. It is a connected documentation and release-content system for fast-shipping product teams.

## Product Idea

Product and engineering teams already have the raw material for strong docs and release content. AI coding just makes that raw material move faster than the content process:

- PRDs and product specs
- RFCs and design docs
- GitHub PRs and issues
- Linear or Jira tickets
- launch specs
- customer emails and support threads
- Slack decisions
- incident notes
- uploaded PDFs and research notes

The problem is not a blank page. The problem is that the context is scattered.

Plot normalizes that context into Source Blocks, extracts change signals, and turns those signals into a weekly or launch-specific docs and content catch-up plan.

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

## Core Object

The core object in Plot is the **Source Block**.

A Source Block is a normalized unit of product or engineering context that Plot can use for docs, release content, brand-voice adaptation, and claim decisions.

Examples:

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

Connections, uploads, paste, URLs, and email forwards are all input methods. They all produce Source Blocks.

## Product Principles

**Every input becomes a block.**  
Plot does not treat uploads, connections, URLs, and paste as separate product worlds. They all become Source Blocks.

**Connections are live block factories.**  
A connected source keeps producing new Source Blocks over time. Those blocks can trigger autonomous product and docs signals.

**The Catch-up Brief is the home screen.**  
Plot should not start from a blank composer. The primary experience is: "This shipped, these sources support it, this content is behind, and this is the brand-voice draft to review."

**Source-backed by default.**  
Plot should not only generate plausible copy. Important product claims should map back to Source Blocks and Signals.

## Origin

Plot started as a strategic pivot from [Tyquill](https://github.com/tyquill).

Tyquill focused on collecting sources and generating drafts. Plot keeps the useful insight behind that workflow, but changes the center of the product: from broad AI writing assistance to connected documentation intelligence for product teams.

The core shift is:

```txt
Tyquill: save sources -> generate drafts
Plot: inputs -> Source Blocks -> Signals -> Catch-up Brief -> Release Pack -> Review -> Follow-up Update
```

Plot is being built from scratch as a new product, with a clearer domain model around Source Blocks, source-backed product claims, brand voice, catch-up briefs, release packs, and dynamic agent workflows.

## Target Users

Initial:

- product-led founders
- small product and engineering teams
- AI/devtool teams
- DevRel and developer experience teams
- teams shipping frequent product changes

Expansion:

- product marketers
- DevRel teams
- B2B launch teams
- solutions and sales engineering teams

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
