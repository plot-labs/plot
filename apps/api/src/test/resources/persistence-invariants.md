# Persistence Migration Invariant Manifest

This manifest is the U1 characterization baseline for the jOOQ migration. It records the current production owner, the database protocol that must remain observable, and the regression evidence required before that owner is cut over. A `verify` entry is an execution-time gap, not permission to infer a new behavior.

## Ownership and acceptance rules

- A slice cannot cut over until its row has a passing characterization or parity test and its generated-type boundary is checked.
- A durable workflow row must preserve eligibility predicate, queue order, lock scope/order, stale threshold, retry counter, idempotency/fingerprint rule, transition guard, and affected-row outcome.
- Tests use direct SQL/JDBC only as an independent oracle. Production `src/main` cleanup does not remove these test fixtures.
- Concurrency evidence uses two real PostgreSQL connections coordinated by barriers/latches; timing sleeps are not evidence.
- PostgreSQL-managed columns (`activity_sequence`, defaults, trigger values, and sequence-backed values) are read-only to application writes.

## JPA slices

| Current owner | Tables / contract | Implicit behavior to characterize | Required evidence | Target |
| --- | --- | --- | --- | --- |
| `worksession/WorkSessionRepository.kt` | `work_sessions`; recent list ordered by `coalesce(last_activity_at, created_at) desc, created_at desc` | dirty checking on PATCH, generated timestamps, not-found mapping, no final ID tie-breaker | `WorkSessionApiIntegrationTest.createListAndUpdateSession`, separate SQL read after PATCH, list ordering fixture | U2 |
| `source/SourceScopeRepository.kt` | `source_scopes`; scope lookup and membership state | entity mapping/nullability, source scope ownership, not-found behavior | Source scope API/import tests; direct state assertions | U3 |
| `writingblock/WritingBlockRepository.kt` | `writing_blocks`, `writing_block_scopes`; page/count/order and `activity_sequence` | `Page`/`Pageable` contract, `NULLS LAST`, four-key order, trigger-managed sequence, JPQL `exists` | `WritingBlockApiIntegrationTest`, `RoutineWorkerIntegrationTest`, cursor and direct SQL assertions | U3/U5 |
| `workspace/UserRepository.kt` | `users`; account identity and bootstrap lookup | uniqueness/error taxonomy, generated timestamps, bootstrap race | account/bootstrap integration tests and direct SQL | U3 |
| `workspace/WorkspaceRepository.kt` | `workspaces`; create/update/read | `saveAndFlush`, dirty checking, owner creation atomicity, authorization | `WorkspaceApiIntegrationTest`, account/bootstrap, separate SQL read after PATCH | U3 |
| `workspace/WorkspaceMemberRepository.kt` | `workspace_members`; active membership/role | uniqueness, owner membership atomicity, role/status predicates | workspace/auth/entitlement integration tests and direct SQL | U3 |

## Durable persistence protocols

| Current owner | Protocol that must remain | Required evidence | Target |
| --- | --- | --- | --- |
| `artifact/run/ArtifactRunPersistence.kt` | artifact-run state transitions and affected-row guards; transaction ownership | artifact run integration and rollback/error tests | U5 |
| `artifact/workflow/ArtifactWorkflowAdmissionPersistence.kt` | idempotent workflow admission, input snapshots, run creation, and trial capacity | workflow admission and artifact API tests | U7 |
| `artifact/workflow/ArtifactWorkflowExecutionPersistence.kt` | model-call lease, checkpoint, retry, failure, and source-access fencing | `ArtifactWorkflowReliabilityIntegrationTest`, physical-attempt tests | U7 |
| `artifact/workflow/ArtifactWorkflowQueryPersistence.kt` | workflow state, run timing, and materialized payload projections | workflow API and timing tests | U7 |
| `artifact/workflow/ArtifactWorkflowRecoveryPersistence.kt` | stale claim and invocation recovery | `ArtifactWorkflowRunRecoveryIntegrationTest`, continuous claimant tests | U7 |
| `artifact/workflow/ArtifactWorkflowMaterializationPersistence.kt` | evidence and final artifact materialization writes | workflow materialization and export tests | U7 |
| `artifact/workflow/ArtifactWorkflowConfiguration.kt` | current transaction-template boundaries and worker lifecycle wiring | workflow rollback, shutdown, after-commit tests | U7 |
| `routine/RoutinePersistence.kt` | routine eligibility/order, retry counters, state transitions | `RoutineWorkerIntegrationTest`, routine migration/background tests | U5 |
| `routine/RoutineAgentPersistence.kt` | agent idempotency, fingerprint conflict, step/run state and affected-row fencing | `RoutineAgentMigrationIntegrationTest`, `AgentRunWorkerIntegrationTest` | U7 |
| `routine/AgentRunAdmissionPersistence.kt` | routine/Chat AgentRun admission and frozen input seeding | `RoutineAgentMigrationIntegrationTest`, admission tests | U7 |
| `routine/AgentRunQueryPersistence.kt` | AgentRun, input, source, step, timing, and artifact projections | `AgentRunWorkerIntegrationTest`, read-path tests | U7 |
| `routine/AgentRunExecutionPersistence.kt` | claim/recovery/transition-version fencing, step/attempt limits, handoff, retry, and terminal transitions | `AgentRunWorkerIntegrationTest`, workflow reliability tests | U7/U10 |
| `github/GitHubReleaseRequestPersistence.kt` | release request admission, range/evidence linkage, and activity projections | `GitHubReleaseLifecycleIntegrationTest`, range/recovery tests | U6 |
| `github/GitHubReleaseLeasePersistence.kt` | release queue order, claim/lease recovery, scope serialization, stale-owner rejection, terminal transitions | `GitHubReleaseLifecycleIntegrationTest`, `GitHubReleaseDraftRecoveryIntegrationTest` | U6 |
| `github/GitHubWebhookDeliveryPersistence.kt` | webhook delivery idempotency and disposition state | `GitHubWebhookAfterCommitIntegrationTest`, `GitHubWebhookApiIntegrationTest` | U6 |
| `github/GitHubRepositoryMonitoringPersistence.kt` | monitoring eligibility, claim/retry/failure transition and stale recovery | `GitHubRepositoryMonitoringPersistenceIntegrationTest` | U6 |
| `github/GitHubRepositoryAccessCheckPersistence.kt` | access-check eligibility, claim/retry/failure transition and stale recovery | `GitHubRepositoryAccessCheckIntegrationTest` | U6 |
| `writingblock/WritingBlockImportService.kt` | workspace-activity advisory lock then source-object advisory lock; partial-index `ON CONFLICT` semantics | writing-block import and routine cursor tests; two-connection lock test | U5 |

## Typed transition boundary

`AgentRunExecutionPersistence` and `GitHubReleaseLeasePersistence` use generated jOOQ fields for claim, renewal, retry/release, recovery, fencing, and terminal status mutations. Complex projections, evidence joins, and remaining SQL-shaped reads stay on `JooqSqlExecutor`; affected-row checks and workspace/owner/version predicates remain mandatory.

## After-commit and transaction boundaries

| Site | Current policy to preserve | Evidence |
| --- | --- | --- |
| `github/GitHubConnectionService.kt` access-check dispatch | callback after committed connection state | `GitHubConnectionApiIntegrationTest`; add commit/rollback visibility proof before cutover |
| `github/GitHubConnectionService.kt` monitoring dispatch | callback after committed monitoring state | monitoring integration; add rollback/no-dispatch proof before cutover |
| `github/GitHubReleaseRetryService.kt` release retry dispatch | callback after transaction commit | `GitHubReleaseDraftRecoveryIntegrationTest` |
| `artifact/workflow/ArtifactWorkflowRunService.kt` workflow dispatch | callback after committed run state | artifact workflow reliability/after-commit coverage |
| `github/GitHubWebhookService.kt` webhook dispatch | callback after webhook transaction | `GitHubWebhookAfterCommitIntegrationTest` |

All five sites must be checked for active-transaction requirements and for any immediate fallback path. A callback must run once after commit, never after rollback, and only after an independent connection can observe the committed state.

## Production `JdbcTemplate` inventory

The following 23 files are the production JDBC migration surface. The named owner is the unit that must remove the call site; the risk is a sequencing hint, not a claim that a class is protocol-free.

| File | Risk / first treatment | Target |
| --- | --- | --- |
| `entitlement/WorkspaceEntitlementReader.kt` | low-risk read projection | U4 |
| `github/GitHubReleaseScopeResolver.kt` | low-risk lookup | U4 |
| `billing/PolarSubscriptionService.kt` | billing state read/write; preserve error mapping | U4 |
| `artifact/run/ArtifactRunPersistence.kt` | bounded state write | U5 |
| `routine/RoutinePersistence.kt` | routine state and cursor | U5 |
| `writingblock/WritingBlockImportService.kt` | advisory locks and upsert | U5 |
| `github/GitHubConnectionService.kt` | after-commit and lifecycle | U6 |
| `github/GitHubImportService.kt` | import idempotency and duplicate error | U6 |
| `github/GitHubInstallationStateService.kt` | installation lifecycle | U6 |
| `github/GitHubReleaseEvidenceService.kt` | evidence linkage and JSONB | U6 |
| `github/GitHubSourceAccessLifecycleService.kt` | source access lifecycle | U6 |
| `github/GitHubReleaseRequestPersistence.kt` | request admission/range/evidence/activity projections | U6 |
| `github/GitHubReleaseLeasePersistence.kt` | queue claim/recovery/fencing and terminal transitions | U6 |
| `github/GitHubWebhookDeliveryPersistence.kt` | webhook delivery idempotency/disposition | U6 |
| `github/GitHubRepositoryAccessCheckPersistence.kt` | queue claim/retry | U6 |
| `github/GitHubRepositoryMonitoringPersistence.kt` | queue claim/retry | U6 |
| `artifact/workflow/ArtifactWorkflowExecutionPersistence.kt` | workflow transition mutations retained as SQL-shaped operations | U7 |
| `artifact/ArtifactRevisionService.kt` | revision and sentence mutation | U7 |
| `artifact/ArtifactExportService.kt` | export idempotency, warnings, citations, and public-source policy | U7 |
| `artifact/workflow/ArtifactWorkflowConfiguration.kt` | transaction wiring | U7 |
| `routine/AgentRunExecutionPersistence.kt` | typed AgentRun transition mutations | U7/U10 |
| `routine/RoutineAgentPersistence.kt` | agent idempotency/fencing | U7 |
| `routine/ChatAgentAdmissionService.kt` | admission/idempotency | U7 |
| `routine/GitHubChangeRoutineService.kt` | transaction-template routine dispatch | U7 |
| `routine/GitHubRoutineRefreshService.kt` | refresh state and retry | U7 |
| `routine/ReadOnlyAgentTools.kt` | read-only agent queries | U4/U7 |

Service SQL without a `JdbcTemplate` import is included in the same inventory when U1's structural scan identifies it; it must be extracted to a feature-local adapter before generated types enter the service layer.

## Implementation closure notes

- WorkSession and Workspace PATCH tests read the committed row through an independent JDBC connection after the API call; the response body is not the only persistence assertion.
- The JPA-only `saveAndFlush` boundary no longer exists after the final entity/repository removal. jOOQ statements execute immediately, while owner/workspace multi-write atomicity is covered by the rollback fixture and DevBootstrap membership tests.
- `SourceScopePersistenceIntegrationTest` covers SQL `NULL` versus JSON `null`, nested JSON values, and PostgreSQL `timestamptz` microsecond precision at the jOOQ mapping boundary.
- Worker claim/transition operations use proxy-visible Spring transactions and complete before external GitHub/model I/O; the named worker and background integration suites remain the regression evidence.
- Keep this manifest in sync with structural scans; an unlisted production persistence owner is a U1 failure.
