<div align="center">
  <img src="apps/web/public/plot-icon.svg" alt="Plot logo" width="88" />
  <h1>Plot</h1>
  <p><strong>Ship fast. Write less. Stay in your team's voice.</strong></p>
  <p>
    Autonomous post-shipping update workspace for teams whose docs, release
    notes, changelogs, customer updates, and launch content need to keep up with
    the speed of AI-assisted shipping.
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
    <img alt="Approval: human approved" src="https://img.shields.io/badge/approval-human_approved-030303?style=flat-square">
  </p>
</div>

[English](README.md) | [한국어](README.ko.md)

## What Is Plot?

AI coding agents are making product and engineering teams ship faster, but the
writing that follows shipped work still moves at the old speed.

Plot starts from what actually shipped, turns connected source records into
**Writing Blocks**, and prepares source-backed update packs in the team's voice.
The product surface is the review workspace: sessions, sources, packs, voice,
and approvals.

Plot is not a generic AI writer or a company knowledge layer. Agents prepare the
work; humans approve what goes out.

## Product Loop

```txt
Ask for an update
  -> choose shipping window and sources
  -> inspect Writing Blocks
  -> apply template and voice
  -> generate an update pack
  -> review claims, evidence, and style
  -> approve channel-ready variants
```

## What Plot Does

- Creates update packs from shipped product work, not from a blank page.
- Keeps claims linked to the source blocks that justify them.
- Produces channel variants for changelogs, docs updates, customer updates,
  launch drafts, and social copy.
- Keeps voice/style explicit through examples, rules, templates, and review
  checks.
- Keeps publishing human-approved.

## Product Surface

| Surface | Role |
| --- | --- |
| Sessions | Chat-like work sessions where a person or agent asks for an update pack and follows progress. |
| Sources | Shipped-work timeline, source selection, imports, and coverage. |
| Packs | Draft and approved update packs with channel variants and review state. |
| Voice | Team voice, channel styles, samples, and explicit rules. |
| Settings | Workspace, members, permissions, and source connections. |

## How It Works

- Connect shipped-work sources and choose the release window that needs an
  update.
- Writing Blocks for PRs, issues, commits, tags, releases, and comparison
  ranges.
- Direct model-provider API calls for initial generation.
- Content templates and voice profiles for repeatable output.
- Claim, source, and style review before approval.
- Publishing stays human-approved.

## Architecture

Plot is a monorepo with a generated Next.js frontend and a backend service. The
domain model is intentionally framework-neutral so the production backend can be
chosen for cost, deployment, and operational fit.

```txt
apps/
  web/  Next.js app
  api/  reserved for the backend service once the runtime is selected

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

The backend is intentionally kept as one service while the core update loop is
being validated. Initial AI generation is a direct model-provider API call
managed by that service; deeper orchestration, authenticated integrations, and
automation can be added after the core update loop works.

## Development

```bash
pnpm dev:web
pnpm build:web
pnpm lint:web
```

## Docs

- [Product Definition](docs/product/product-definition.md)
- [Agentic UX Direction](docs/product/agentic-ux.md)
- [Project Structure](docs/architecture/project-structure.md)
- [Data Architecture](docs/architecture/data-architecture.md)
- [Data ERD](docs/architecture/data-erd.mmd)
- [ADR 0001: Monorepo With Generated Apps](docs/decisions/0001-monorepo-generated-apps.md)
