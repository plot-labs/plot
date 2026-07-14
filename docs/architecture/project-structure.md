# Project Structure

Plot is a monorepo with a Next.js frontend and a Kotlin Spring Boot backend.
The product/data
architecture remains documented in framework-neutral terms so the domain model
does not depend on persistence or agent implementation details.

## Top Level

```txt
plot/
  apps/
    web/
    api/          # Kotlin Spring Boot backend and agent runtime
  packages/
    api-client/
    ui/
    config/
  infra/
    docker/
    storage/
  docs/
    product/
    architecture/
    decisions/
    api/
    operations/
```

## Frontend

`apps/web` owns product UI, landing routes, authenticated app routes, and
frontend feature modules.

```txt
apps/web/src/
  app/
    (marketing)/        # reserved route group (landing currently lives at app/page.tsx)
    (auth)/             # reserved route group
    (app)/
      sessions/
      sources/
      packs/
      voice/
      workspaces/[workspaceId]/settings/
    api/                # serverless routes (waitlist)
  components/
    landing/
    layout/
    ui/
  features/
    citations/          # sentence review, inline evidence, conflict, export
    sessions/           # real generation path plus seeded unrelated sessions
    sources/            # implemented (mock data)
    packs/              # generated pack detail plus seeded pack list
    agent-trace/        # placeholder
    angles/             # placeholder
    blocks/             # placeholder
    brief/              # placeholder
    connections/        # placeholder, source adapter setup
    content-pack/       # placeholder
    memory/             # placeholder
    signals/            # placeholder
  lib/                  # shared client boundary, polling, dev-context, waitlist
```

Sessions discovers imported GitHub Writing Blocks through the same-origin
`/api/plot` BFF, creates and polls durable generation runs, resolves conflicts,
and opens structured cited packs. Unrelated product surfaces still use seeded
dev data while they migrate independently. `packages/api-client` owns the
shared generation/citation contract used by the web boundary.

## Backend

`apps/api` owns the Kotlin Spring Boot backend service. v0 uses a lightweight
backend-managed update-agent loop plus direct model-provider calls, not a
separate agent service. The module boundaries below describe the backend domain
shape and should stay independent from specific Spring infrastructure choices.

Implemented today: workspace/dev context, GitHub connection/import,
Writing Blocks, durable generation workflow/recovery, structured OpenAI model
gateway, content packs, sentence revisions/citations, conflict intervention,
and audited export. Integration tests use PostgreSQL through Testcontainers;
the real-model contract test is explicit and opt-in.

```txt
apps/api/
  src/main/kotlin/com/plot/api/
  common/
  config/
  security/
  workspace/
  user/
  input/
    repository/
    watch/
    import/
    normalizer/
    upload/             # later supporting input
    paste/              # later supporting input
    url/                # later supporting input
    email/              # later supporting input
  connection/           # source adapter setup
  sync/                 # lightweight repository watch refresh
  webhook/              # later automation
  integration/          # later authenticated sources
  block/
  template/
  voice/
  generation/           # fixed durable write/review/rewrite state machine
  contentpack/          # structured sentences, revisions, Markdown export
  ai/
    provider/            # disabled/fake/OpenAI structured gateways
    prompt/              # untrusted evidence and sentence delimiters
```

The current workflow is intentionally fixed and backend-owned. Dynamic agent
planning, tool selection, self-directed loop construction, and multi-agent
delegation remain deferred; they are not hidden inside the web client or model
prompt.

## First Implementation Order

1. Workspace and auth foundation
2. First source adapter, repository watches, repository imports, Writing Block
   model, and input endpoints
3. Voice Profile, Voice Samples, Voice Rules, and system Content Templates
4. Generation Run state machine for snapshot, draft, review, targeted rewrite,
   conflict intervention, and citation mapping — implemented for changelogs
5. Content Pack, sentence revision, export API, and citation UX — implemented
6. Voice Profile, Voice Samples, Voice Rules, and additional system templates
7. Later: dynamic agent planning, upload/paste/url/email inputs, additional
   providers, retention controls, and publishing automation
