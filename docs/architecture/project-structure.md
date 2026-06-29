# Project Structure

Plot is a monorepo with a generated Next.js frontend and a generated Spring Boot backend.

## Top Level

```txt
plot/
  apps/
    web/
    api/
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

`apps/web` owns product UI, landing routes, authenticated app routes, and frontend feature modules.

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
    brief/
    blocks/
    signals/
    angles/
    content-pack/
    memory/
    connections/
    agent-trace/
  lib/
```

## Backend

`apps/api` owns the core backend and the dynamic loop agent runtime in one Spring Boot process.

```txt
apps/api/src/main/java/com/plot/
  common/
  config/
  security/
  workspace/
  user/
  input/
    upload/
    paste/
    url/
    email/
    normalizer/
  connection/
  sync/
  webhook/
  integration/
  block/
  signal/
  brief/
  angle/
  contentpack/
  audience/
  memory/
  agent/
    runtime/
    planner/
    evaluator/
    tool/
    policy/
    trace/
    prompt/
  job/
```

## First Implementation Order

1. Workspace and auth foundation
2. Writing Block model and input endpoints
3. Signal extraction jobs
4. Weekly Brief and Angle generation
5. Content Pack generation
6. Agent run and step trace

