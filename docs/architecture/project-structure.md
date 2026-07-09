# Project Structure

Plot is a monorepo with a generated Next.js frontend and a backend service
scaffold. The backend runtime is Kotlin Spring Boot; the product/data
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
    sessions/           # implemented (mock data)
    sources/            # implemented (mock data)
    packs/              # implemented (mock data)
    agent-trace/        # placeholder
    angles/             # placeholder
    blocks/             # placeholder
    brief/              # placeholder
    connections/        # placeholder, source adapter setup
    content-pack/       # placeholder
    memory/             # placeholder
    signals/            # placeholder
  lib/                  # api-client.ts (dev boundary), dev-context.ts, waitlist.ts
```

The product shell currently renders seeded dev data from `lib/dev-context.ts`
through `lib/api-client.ts`; it does not call the backend yet.

## Backend

`apps/api` owns the Kotlin Spring Boot backend service. v0 uses a lightweight
backend-managed update-agent loop plus direct model-provider calls, not a
separate agent service. The module boundaries below describe the backend domain
shape and should stay independent from specific Spring infrastructure choices.

Implemented today: `common/`, `dev/` (dev bootstrap context), `workspace/`,
`worksession/`, `task/`, and `writingblock/`, each with entity, repository,
service, controller, and DTO layers plus Testcontainers-backed integration
tests. The remaining modules below are the planned target shape.

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
  agent/
  generation/
  contentpack/
  citation/
  ai/
    provider/
    prompt/
```

## First Implementation Order

1. Workspace and auth foundation
2. First source adapter, repository watches, repository imports, Writing Block
   model, and input endpoints
3. Voice Profile, Voice Samples, Voice Rules, and system Content Templates
4. Agent Run state machine for import, draft, and citation mapping
5. Generation Run, Generation Target, and Model Invocation endpoints backed by
   a direct model-provider API call
6. Content Pack and Content Variant UI
7. Citation/source-reference and style guidance UI
8. Later: upload/paste/url/email source inputs and publishing automation
