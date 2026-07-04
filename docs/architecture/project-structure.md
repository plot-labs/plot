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
    (marketing)/
    (auth)/
    (app)/
  components/
    layout/
    ui/
  features/
    blocks/
    repositories/
    imports/
    watches/
    templates/
    voice/
    agents/
    drafts/
    content-pack/
    claims/
    connections/        # source adapter setup
  lib/
```

## Backend

`apps/api` owns the Kotlin Spring Boot backend service. v0 uses a lightweight
backend-managed update-agent loop plus direct model-provider calls, not a
separate agent service. The module boundaries below describe the backend domain
shape and should stay independent from specific Spring infrastructure choices.

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
  claim/
  ai/
    provider/
    prompt/
```

## First Implementation Order

1. Workspace and auth foundation
2. First source adapter, repository watches, repository imports, Writing Block
   model, and input endpoints
3. Voice Profile, Voice Samples, Voice Rules, and system Content Templates
4. Agent Run state machine for import, draft, claim mapping, and review queue
5. Generation Run, Generation Target, and Model Invocation endpoints backed by
   a direct model-provider API call
6. Content Pack and Content Variant UI
7. Claim, evidence, and style review UI
8. Later: upload/paste/url/email source inputs and publishing automation
