# GitHub Adapter Backend Design

**Date:** 2026-07-12
**Status:** Approved design with subsequent provider-neutral amendments
**Scope:** Backend-only GitHub App connection and manual merged-PR import

## Goal

Implement the minimum backend GitHub adapter that connects a GitHub App installation to the current development workspace, imports merged pull requests for a requested time window, and stores them directly as idempotent Writing Blocks without a separate provider-source table or draft generation.

## Product Boundary

This design validates that real shipped-work data can enter Plot and become reusable source material. A GitHub connection that cannot import records is not considered useful. Draft and update-pack generation are separate features and are not required for this backend slice.

The backend and frontend are separate delivery units. This design provides the API contract needed by a later frontend plan but does not implement installation, repository-selection, import, or Sources UI.

### In scope

- GitHub App installation flow for source access
- Current `DevContext` user and workspace scoping
- Installation repository discovery and multiple selected repository connections
- Manual, synchronous import for a caller-supplied UTC interval
- Merged pull requests selected by `merged_at`
- Direct GitHub pull-request-to-Writing-Block transformation and persistence
- Import status and result APIs
- Idempotent re-import

### Out of scope

- Plot sign-in or production authentication
- GitHub OAuth user authorization
- Frontend UI
- Independent commit ingestion
- Drafts, generation runs, or update packs
- Background job execution
- GitHub webhooks and continuous synchronization
- Repository watches or scheduled refresh
- Automatic retries

The import processing boundary must remain reusable. A later webhook handler or background job runner should invoke the same Writing Block transformation and upsert service without duplicating provider logic.

## Architecture

### GitHub App installation service

The installation service creates a GitHub App installation URL, issues and verifies a short-lived signed `state`, receives an `installation_id`, and lists repositories granted to that installation. The state binds the handoff to the current development user and workspace and rejects tampering, expiry, or cross-workspace reuse.

The service creates short-lived GitHub App JWTs and exchanges them for installation access tokens. Installation tokens and the GitHub App private key are never stored in PostgreSQL or returned through APIs.

### GitHub API adapter

The adapter owns GitHub-specific request and response types. It lists pull requests for the selected repository, follows pagination, and returns an internal source DTO. Only pull requests with a non-null `merged_at` inside the requested half-open interval `[from, to)` are eligible.

The adapter maps GitHub failures into stable application errors. Raw GitHub response bodies, credentials, and tokens must not escape this boundary.

### Import orchestrator

The orchestrator owns the lifecycle for every accepted import attempt:

```text
RUNNING -> COMPLETED | FAILED
```

The reservation insert creates the attempt directly as `RUNNING`; a repository-overlap loser is rejected before attempt creation and has no import ID. The orchestrator validates the request, records the import attempt, obtains all eligible GitHub records, and invokes transactional persistence. GitHub network access happens before the persistence transaction. Once all pages have been fetched successfully, Writing Block upserts, counters, and the `COMPLETED` transition happen in one database transaction.

If fetching, transformation, or persistence fails, no partial Writing Block data is committed. After rollback, a separate transaction marks the import `FAILED`.

### Writing Block transformer

The transformer maps an in-memory GitHub pull-request DTO into the provider-neutral Writing Block model. It creates or updates one GitHub PR Writing Block per external PR ID within a source repository. A Writing Block is imported evidence, not generated draft content.

The transformer must be deterministic for the same provider DTO. It must not call GitHub, generate prose, or own transaction boundaries.

## Data Model

### `connections`

Represents a workspace-scoped provider connection. The first concrete value is
a GitHub App installation; the same aggregate can later represent a Linear,
Jira, or Slack authorization without changing the Writing Block identity model.

Required concepts:

- provider and connection kind (GitHub uses `GITHUB` and
  `GITHUB_APP_INSTALLATION`)
- provider-neutral external connection key (GitHub stores the installation ID as text)
- external account identity when available
- granted permissions and repository-selection snapshot
- status such as `ACTIVE`, `NEEDS_REAUTH`, `DISABLED`, or `ERROR`
- timestamps and creating development user

No raw installation access token is stored. Runtime secrets provide the App ID and private key.

### `source_namespaces`, bindings, and scopes

`source_namespaces` own canonical provider identity independently of credentials.
`connection_namespace_bindings` associate a current authorization with that
identity, allowing reinstallation or credential rotation without duplicating
content. `source_scopes` represent collections and queries that yield Writing
Blocks. GitHub uses a repository namespace and `scope_kind=REPOSITORY`; future
adapters may use Slack channels, Linear/Jira projects, or Drive corpora.

Repository ownership and every downstream reference are workspace-scoped.

### `source_imports`

Represents one manual import attempt. It stores the workspace, source scope,
observation, requested `from` and `to` instants, status, start and completion
timestamps, imported count, and failure details.

Every retry creates a new import record, including retries for the same repository and interval. An import that finds zero eligible pull requests completes successfully with count zero.

Externally visible failures use a stable error code and safe message. Internal diagnostics are stored separately and must not contain secrets.

### `writing_blocks`

Each imported pull request maps directly to one GitHub PR Writing Block.
Source-managed identity is unique on `(workspace_id, source_namespace_id,
source_kind, external_object_key)`. Scope membership is stored separately so
one canonical block can be observed through multiple scopes or credentials.
Provider-specific details such as PR number and branches live in constrained
metadata rather than duplicate raw-source tables.

## API Contract

The backend exposes endpoints for these capabilities:

1. Return a GitHub App installation URL for the current workspace.
2. Validate installation callback data and list repositories granted to the installation.
3. Connect, inspect, and disconnect any of the granted repositories; multiple repositories may be active in one workspace.
4. Start a synchronous import for a source repository and `[from, to)` interval.
5. Retrieve an import and its status, counters, and safe error details.
6. List the resulting Writing Blocks by selected repository through the workspace-scoped surface.

Exact route names and DTO field names belong in the implementation plan, where they can be aligned with existing controller conventions. The contract must allow a later frontend to complete installation, select a repository, request an import, and show results without changing backend behavior.

## Import Semantics

### Time interval

The request uses UTC instants and requires `from < to`. Eligibility is based only on GitHub `merged_at`:

```text
from <= merged_at < to
```

PR creation and update timestamps do not determine inclusion. Unmerged and closed-without-merge pull requests are excluded.

### Idempotency

Every accepted import attempt remains visible in `source_imports`, while Writing
Blocks are upserted by workspace, namespace, source kind, and external object
key. A rejected overlapping request is deliberately not an accepted attempt
and therefore has no lifecycle row.

Running the same interval repeatedly must not increase source or Writing Block counts unless GitHub contains newly eligible PR identities. Existing records may be refreshed from the latest GitHub representation.

### Atomicity

The adapter must fetch every required GitHub page before persistence starts. Writing Block transformations and upserts are committed atomically. Any transformation or persistence failure rolls back the entire data transaction. The failed import status is recorded afterward in a separate transaction.

### Connection failures

Revoked permissions or an invalid installation move the connection to `NEEDS_REAUTH` or `DISABLED` as appropriate. Rate limits and transient GitHub failures are classified as retryable, but this scope does not retry automatically. A caller can submit a new import attempt.

## Security

- Sign installation state and bind it to the current user and workspace.
- Give installation state a short expiry and reject replay across contexts.
- Keep the GitHub App private key in runtime secret configuration.
- Generate short-lived installation tokens only when calling GitHub.
- Never log or persist raw credentials, installation tokens, or private keys.
- Apply workspace ownership checks before external calls.
- Return stable, sanitized error codes and messages instead of raw GitHub payloads.
- Request only the GitHub App permissions required to read repository metadata and pull requests.

## Testing Strategy

### Unit tests

- installation URL and signed-state creation
- invalid signature, expired state, and workspace mismatch
- App JWT and installation-token exchange behavior
- GitHub pagination and error mapping
- exact `[from, to)` boundary behavior for `merged_at`
- exclusion of unmerged pull requests
- deterministic source-to-Writing-Block transformation

### Integration tests

Use the existing Spring Boot integration-test conventions, a fake GitHub adapter, and Testcontainers PostgreSQL to verify:

- connection and repository lifecycle
- workspace isolation
- zero-result successful imports
- Writing Block creation
- repeated import upserts without duplicates
- independent import-attempt history
- complete rollback when transformation or persistence fails
- `FAILED` status written after rollback
- database uniqueness, foreign-key, and workspace constraints
- API response contracts and sanitized failures

CI must not call the live GitHub API. A documented development smoke test validates a real GitHub App installation, repository selection, and import against a controlled repository.

## Completion Criteria

The backend slice is complete when:

1. It creates and verifies a workspace-bound GitHub App installation handoff.
2. It lists repositories granted to the installation and persists selected repositories as namespace-backed source scopes.
3. It accepts a manual synchronous import for a UTC `[from, to)` interval.
4. It imports only pull requests whose `merged_at` falls inside the interval.
5. It transforms and atomically upserts corresponding Writing Blocks directly.
6. Repeating an import does not duplicate Writing Blocks.
7. Any fetch, persistence, or transformation failure leaves no partial domain data and records a failed import attempt.
8. APIs expose connection, import, and repository-filtered Writing Block results for later frontend use.
9. Automated tests cover security boundaries, filtering, idempotency, atomicity, and workspace isolation.

## Follow-up Work

Follow-up designs should remain separate:

1. Frontend installation, repository selection, import, and Sources UI.
2. Background job execution for long-running imports.
3. Signed GitHub webhook ingestion for merged pull requests.
4. Repository watches and scheduled reconciliation.
5. Draft and update-pack generation from Writing Blocks.

The background and webhook paths should reuse the import processing boundary introduced here rather than create provider-specific duplicate persistence or transformation paths.
