# Plot System Architecture

This document describes the current runtime boundaries and ownership model. It is deliberately narrower than a product roadmap: class and command references below are the implementation contract for this checkout.

## System Boundaries

Plot has four runtime boundaries:

1. **Browser application.** `apps/web` renders Chat, Routines, Artifacts, settings (including Integrations), and billing surfaces. `packages/api-client/src/index.ts` is the typed browser-facing API client.
2. **Same-origin BFF.** `apps/web/src/app/api/plot/[...path]/route.ts` validates browser origin, session state, route segments, and forwarded headers. It exchanges the authenticated session for a short-lived server JWT and forwards only the allowlisted request to the Kotlin API.
3. **Kotlin resource server.** `apps/api` owns workspace authorization, product use cases, durable workers, and provider integrations. PostgreSQL is the system of record; Flyway migrations under `apps/api/src/main/resources/db/migration` define the schema.
4. **External systems.** GitHub supplies repository and release events, model providers perform bounded Agent/Artifact work, and Polar supplies billing subscription events. External calls happen after the relevant durable claim or admission boundary is established.

The five product surfaces are:

- **Chat:** interactive AgentRun execution and source-grounded artifact work.
- **Routines:** scheduled or explicitly started AgentRun execution.
- **Artifacts:** editable, revisioned, source-cited documents and Markdown export.
- **GitHub release automation:** release webhook ingestion, range detection, evidence capture, and draft generation.
- **Billing/entitlement:** plan, subscription, trial, and workspace access policy.

Source evidence is not a standalone browser surface. GitHub connections, repository scopes, and writing blocks are configured in Integrations (workspace settings) and consumed from Chat, Routines, and Artifacts. The Kotlin API still owns source lifecycle state, writing block persistence, and repository scope enforcement.

## Request Authorization

A browser request follows this order:

1. The API client sends `x-plot-workspace-id` for workspace-scoped calls and `idempotency-key` for retryable mutations.
2. The BFF authenticates the Better Auth session, rejects unsafe origins and paths, filters request/response headers, and never forwards browser-controlled authorization material as trusted identity.
3. The BFF signs a server JWT with the configured issuer and audience.
4. `SecurityConfig.kt` validates the JWT signature, issuer, and audience at the Kotlin boundary. `RequestActor.kt` resolves the authenticated user and workspace context.
5. Workspace membership and entitlement checks run before the use-case service performs a read or mutation. Workspace predicates remain in persistence SQL and typed mutations; the client-supplied workspace ID is not an authorization decision by itself.

Local and test authentication bypasses are profile-gated. Production defaults retain JWT validation, CSRF/origin checks at the BFF, and safe error-code mapping without provider-body logging.

## Agent Execution

Chat and Routine execution share one durable lifecycle:

```text
Chat/Routine admission
  -> AgentRunAdmissionPersistence freezes input, source, budget, and idempotency identity
  -> AgentRunWorker claims one AgentRun with owner + transition version
  -> AgentRunExecutionPersistence records steps, tool/model budgets, handoff, retry, and terminal state
  -> ArtifactRunPersistence admits one owned ArtifactRun when evidence is ready
  -> ArtifactWorkflowRunWorker executes durable model checkpoints
  -> ArtifactWorkflowExecutionPersistence / MaterializationPersistence finish the ArtifactRun
```

The ownership rule is relational, not merely conventional:

- one supported AgentRun owns at most one ArtifactRun;
- the ArtifactRun and every Generation attempt share the AgentRun workspace and owner chain;
- a Generation is not a substitute for an AgentRun or ArtifactRun;
- a successful AgentRun requires a materialized ArtifactRun in `READY` or `NEEDS_REVIEW`;
- retry attempts remain children of the same logical ArtifactRun and AgentRun.

Admission, query, and execution responsibilities are separate. `AgentRunQueryPersistence.kt` owns projections and `AgentRunExecutionPersistence.kt` owns worker transitions. Artifact workflow admission, execution, query, recovery, and materialization are separate owners under `apps/api/src/main/kotlin/com/plot/api/artifact/workflow`.

The read-only ownership audit is `apps/api/src/main/resources/db/audit/v1/agent_artifact_ownership.sql`. `AgentArtifactOwnershipInvariantIntegrationTest.kt` verifies schema-enforced and audit-only cases, including legitimate pre-V26 GitHub history.

## GitHub Release Automation

The release path preserves user control over publication:

1. `GitHubWebhookController.kt` validates the webhook request and payload boundary.
2. `GitHubWebhookService.kt` resolves the installation/repository scope, records delivery idempotently, and enqueues a release request. Dispatch occurs after the transaction commits.
3. `GitHubReleaseRequestPersistence.kt` owns request admission, release range/head state, evidence linkage, AgentRun/Artifact linkage, and activity projections.
4. `GitHubReleaseDraftWorker.kt` claims one eligible request through `GitHubReleaseLeasePersistence.kt`, then delegates orchestration to `GitHubReleaseDraftOrchestrator.kt`.
5. Range resolution and evidence capture establish a trustworthy boundary. The first observed tag can finish as `NEEDS_RANGE`; an exact non-empty range reuses the common AgentRun-to-ArtifactRun path.
6. Retry, heartbeat, stale recovery, terminal status, and source-access fencing remain owner/version/transition guarded. `GitHubSourceAccessLifecycleService.kt` fences release work in the same outer transaction as monitoring and Artifact workflow work.

`GitHubWebhookDeliveryPersistence.kt` owns delivery insert/find/mark operations. `GitHubReleasePersistenceContracts.kt` contains the focused interfaces used by coordinators and tests; there is no broad release-persistence façade.

## Ownership Invariants

The database enforces workspace and uniqueness constraints where possible. The audit covers semantic states the schema can represent:

- missing AgentRun or ArtifactRun ownership on a Generation;
- an AgentRun/ArtifactRun/Generation chain that disagrees within one workspace;
- a terminal successful AgentRun whose ArtifactRun is missing, unmaterialized, failed, or not reviewable;
- a newly admitted GitHub release whose release-to-agent/artifact chain is inconsistent.

A null AgentRun link on legitimate pre-V26 GitHub release history is historical data and is excluded by the audit. New supported admissions are covered by the Agent execution and GitHub automation integration suites. The audit is read-only and returns stable violation codes, workspace IDs, entity IDs, and details.

## Persistence Conventions

Persistence owners are feature-local and responsibility-specific:

- AgentRun: `AgentRunAdmissionPersistence.kt`, `AgentRunQueryPersistence.kt`, and `AgentRunExecutionPersistence.kt`.
- Artifact workflow: `ArtifactWorkflowAdmissionPersistence.kt`, `ArtifactWorkflowExecutionPersistence.kt`, `ArtifactWorkflowQueryPersistence.kt`, `ArtifactWorkflowRecoveryPersistence.kt`, and `ArtifactWorkflowMaterializationPersistence.kt`.
- GitHub release: `GitHubWebhookDeliveryPersistence.kt`, `GitHubReleaseRequestPersistence.kt`, and `GitHubReleaseLeasePersistence.kt`.
- Artifact HTTP use cases: `ArtifactQueryService.kt`, `ArtifactRevisionService.kt`, and `ArtifactExportService.kt`.

`JooqSqlExecutor` remains the adapter for complex projections, joins, JSON/text mapping, and SQL-shaped reads. Generated jOOQ fields are used for high-risk state transitions in `AgentRunExecutionPersistence.kt` and `GitHubReleaseLeasePersistence.kt`: claim, renewal, retry/release, stale recovery, fencing, and terminal status mutations. Every converted mutation retains workspace, owner, status, transition-version, time predicates, and affected-row checks.

Transactions are owned by the use case that needs atomicity. External provider/model calls occur outside the database mutation that claims work. After-commit dispatch is used where a callback must observe committed rows, notably GitHub webhook enqueue and release retry.

## Verification

Run the repository-level gates from the root:

```bash
just test-api
just test-api-client
just test-web
just verify-jooq
just lint
just build
```

Focused checks for the ownership and persistence boundaries:

```bash
cd apps/api && ./gradlew test \
  --tests '*AgentArtifactOwnershipInvariantIntegrationTest' \
  --tests '*AgentRunWorkerIntegrationTest' \
  --tests '*ArtifactWorkflowReliabilityIntegrationTest' \
  --tests '*ArtifactWorkflowRunRecoveryIntegrationTest' \
  --tests '*GitHubReleaseLifecycleIntegrationTest' \
  --tests '*GitHubReleaseDraftRecoveryIntegrationTest'
```

`just test-api-client` is a first-class gate for the browser client. `just verify-jooq` regenerates sources against PostgreSQL and compares the checked-in baseline; it is not a license to rewrite unrelated SQL. The persistence invariant manifest at `apps/api/src/test/resources/persistence-invariants.md` is the maintenance checklist for owner paths, protocol evidence, and future structural scans.
