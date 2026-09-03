<div align="center">
  <img src="apps/web/public/plot-icon.svg" alt="Plot logo" width="88" />
  <h1>Plot</h1>
  <p><strong>Ship fast. Write less.</strong></p>
  <p>Turn merged product work into an editable, source-cited changelog.</p>
</div>

[English](README.md) | [한국어](README.ko.md)

## Current Product

Plot combines five product surfaces:

- **Chat** — interactive, source-grounded AgentRun work.
- **Routines** — scheduled or explicitly started AgentRun work.
- **Artifacts** — editable, revisioned, source-cited documents and Markdown export.
- **GitHub release automation** — release webhooks, trustworthy range detection, and draft generation.
- **Billing/entitlement** — subscription, trial, plan, and workspace access policy.

GitHub connections, repository scopes, and writing blocks are configured in **Integrations** (workspace settings) and consumed from Chat, Routines, and Artifacts.

The [design specification](DESIGN.md) is the implementable constitution: UI laws,
product surfaces, voice registers, visual system, component rules, and screen inventory.
The [system architecture overview](docs/architecture/system-overview.md) describes
the request boundary, execution ownership, persistence conventions, and verification
commands. Publishing remains under the user's control outside Plot.

## Repository

```txt
apps/
  web/  Next.js application and same-origin API proxy
  api/  Kotlin Spring Boot API and generation worker

packages/
  auth/        Better Auth configuration and allowlist policy
  api-client/  typed browser client for the Plot API
```

PostgreSQL is the system of record. Flyway migrations under
`apps/api/src/main/resources/db/migration` are the authoritative schema.

## Development

Requirements:

- Java 21
- Bun
- Docker
- `just`

```bash
bun install
just dev-api
just dev-web
```

Validation:

```bash
just lint
just test
just build
```

API integration tests use PostgreSQL through Testcontainers.

## Operations

- [GitHub App development smoke test](docs/operations/github-app-development-smoke-test.md)
- [GitHub release automation](docs/operations/github-release-automation.md)
- [Private repository production certification](docs/operations/private-repository-production-certification.md)
- [Polar subscription webhook](docs/operations/polar-subscription-webhook.md)
