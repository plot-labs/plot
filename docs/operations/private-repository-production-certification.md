# Private repository production certification

This runbook is the launch gate for Plot's narrow private-beta loop. It
certifies one real private GitHub repository through connection, release
boundary resolution, source-cited changelog generation, review, and Markdown
handoff. It is not a general staging procedure or a replacement for the
[GitHub release automation runbook](github-release-automation.md).

## Why the preflight is mandatory

`plot.github.release-automation-enabled` is a process-wide switch. When it is
enabled, every worker connected to the same PostgreSQL database can claim any
runnable release request, regardless of which workspace the operator is
testing. A clean target workspace is therefore necessary but not sufficient:
the database must have no runnable work for any workspace before deployment.

The smoke targets the exact squash-merged `main` commit that will be deployed
to production. Do not certify a branch deployment or a Vercel preview and then
assume the production artifact is equivalent.

## Safety and privacy boundary

- Use the existing approved production workspace only after the read-only
  preflight below proves it has no GitHub connection or runnable work.
- Use the approved real private repository, but do not copy its name, URL,
  owner, tag names, source excerpts, or provider identifiers into Git, a PR,
  Linear evidence, screenshots, or an external trace.
- Create only two unique temporary `plot-smoke-<date>-*` tags on existing
  commits. Delete exactly those tags after review and export validation.
- Do not suspend or uninstall the GitHub App, remove repository grants, delete
  or transfer the repository, delete the workspace, or edit durable rows by
  hand.
- Keep PostgreSQL as the business source of truth. Langfuse is best-effort
  telemetry and must never be used as a recovery queue.
- Keep raw screenshots, downloaded Markdown, SQL output, GitHub payloads, and
  trace exports in a temporary directory outside the repository. Upload only
  sanitized screenshots and a bounded result summary to Linear, then remove
  the raw artifacts.

## 1. Read-only preflight

### Record the candidate and platform state

Pull the squash-merged `main` and record its SHA locally. Do not paste the SHA
with private repository metadata into a public document.

```bash
git fetch origin main
git rev-parse origin/main
fly status --app useplot-api
fly config show --app useplot-api --json > /tmp/plot-fly-config.json
```

Inspect the Fly configuration file and the deployed config, but never print
secret values. The candidate must expose the following non-secret state:

```text
PLOT_AI_ENABLED=true
PLOT_AI_MODEL=openai/gpt-5.4-nano
PLOT_AI_ROUTINGPROVIDER=openai
PLOT_AI_ALLOWFALLBACKS=false
PLOT_AI_CONTENTLOGGINGENABLED=false
SPRING_AI_MODEL_CHAT=openai
PLOT_GITHUB_RELEASEAUTOMATIONENABLED=true
```

Before this candidate is deployed, the currently running production process
must still have AI and release automation disabled. Confirm that from the
deployed config inventory and application startup configuration, not by
guessing from the branch file.

### Check secret names without reading values

```bash
fly secrets list --app useplot-api --json \
  | jq -r '.[].Name' \
  | sort
```

The deployment secret store must contain the names required by the current
application, including the datasource, Better Auth/JWKS, GitHub App and
webhook, OpenRouter/Spring AI, Polar, and Langfuse OTLP credentials. The exact
names are documented in the existing operations runbooks. Do not use `fly ssh
console`, shell history, process listings, or log output to print a secret.

### Source a database connection without printing it

Use the approved credential store or a secure local prompt. Do not place the
production JDBC URL in a file, command history, ticket, or screenshot.

```bash
set +o history
read -r -s JDBC_DATASOURCE_URL
read -r -s PLOT_DB_USERNAME
read -r -s PLOT_DB_PASSWORD
set -o history

export PLOT_PSQL_URL="${JDBC_DATASOURCE_URL#jdbc:}"
```

The JDBC-to-`psql` conversion above removes only the `jdbc:` prefix; retain
the host, database, TLS query parameters, and any URL-encoded values. Use the
result only in the current shell and clear it after the smoke. For every query
below, invoke `psql` with `PGPASSWORD` in the environment rather than putting
the password in the command line:

```bash
PGPASSWORD="$PLOT_DB_PASSWORD" psql "$PLOT_PSQL_URL" \
  -v ON_ERROR_STOP=1 -X -c 'select now();'
```

### Database zero-work preflight

Replace only the placeholder values in the `psql` session. They are not
production identifiers. Keep returned IDs in the restricted local shell; the
Linear evidence summary uses counts and terminal states only.

```sql
\set workspace_id '00000000-0000-0000-0000-000000000000'
\set installation_id '00000000'
\set repository_id '00000000'

select count(*) as workspace_connections
from connections
where workspace_id = :'workspace_id'::uuid
  and provider = 'GITHUB';

select count(*) as workspace_scopes
from source_scopes
where workspace_id = :'workspace_id'::uuid
  and provider = 'GITHUB';

select count(*) as workspace_monitoring_rows
from github_repository_monitoring
where workspace_id = :'workspace_id'::uuid;

select count(*) as workspace_release_boundaries
from github_release_draft_requests
where workspace_id = :'workspace_id'::uuid
  and status = 'NEEDS_RANGE';

select count(*) as workspace_release_requests
from github_release_draft_requests
where workspace_id = :'workspace_id'::uuid;

select count(*) as workspace_generation_runs
from generation_runs
where workspace_id = :'workspace_id'::uuid;

select count(*) as workspace_model_invocations
from model_invocations
where workspace_id = :'workspace_id'::uuid;

select count(*) as workspace_content_packs
from content_packs
where workspace_id = :'workspace_id'::uuid;

select count(*) as workspace_runnable_release_requests
from github_release_draft_requests
where workspace_id = :'workspace_id'::uuid
  and status in ('QUEUED', 'RESOLVING')
  and (next_attempt_at is null or next_attempt_at <= now());

select count(*) as workspace_runnable_generation_runs
from generation_runs
where workspace_id = :'workspace_id'::uuid
  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
  and (next_attempt_at is null or next_attempt_at <= now());

select count(*) as global_runnable_release_requests
from github_release_draft_requests
where status in ('QUEUED', 'RESOLVING')
  and (next_attempt_at is null or next_attempt_at <= now());

select count(*) as global_runnable_generation_runs
from generation_runs
where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
  and (next_attempt_at is null or next_attempt_at <= now());
```

The target workspace must have zero GitHub connections, source scopes,
monitoring rows, release boundaries, release requests, generation runs, model
invocations, and content packs before the smoke. Existing Sessions or Packs
from unrelated earlier work are recorded as before/after deltas rather than
treated as evidence that the database is empty. Global runnable counts must be
zero. If any check is non-zero, stop; do not delete rows or change statuses to
make the preflight pass.

If a deployment uses a different migration name for the boundary projection,
use the actual table from the production schema and record only the resulting
count. Do not broaden this query into a cleanup script.

### Health and provider preflight

- `GET <production-api-origin>/actuator/health` must report healthy after the
  current deployment and again after the candidate starts.
- Verify the GitHub App's Metadata, Pull requests, and Contents permissions.
- Verify Push, Release, Installation, Installation repositories, and Repository
  events are subscribed. Confirm the webhook URL points at the production API
  and that a webhook secret is configured without displaying it.
- Verify the OpenRouter base URL, pinned model, routing provider, no-fallback
  policy, and zero content logging through configuration presence and a
  bounded provider connectivity check. Do not log the authorization header or
  provider response body.
- Verify Langfuse OTLP endpoint, Basic authentication, production resource
  attribute, and exporter enablement through the secret store and deployment
  inventory. Use only opaque correlation IDs in any connectivity probe.
- Confirm the rollback command and operator with permission to disable both
  global switches are ready before deploying.

## 2. Deploy the candidate and connect the repository

1. Deploy the recorded squash-merged `main` SHA using `apps/api/fly.toml`.
2. Confirm the running image/version corresponds to that SHA and health passes.
3. Confirm AI and release automation are enabled without printing config
   values.
4. Sign in through production Web and arrive at **Integrations**.
5. Connect the approved private repository from the existing clean workspace.
   The customer path is connect-only: it must not offer a 30-day import or
   create onboarding Writing Blocks.
6. Confirm the request returns before release-convention analysis completes.
   The UI must show a usable Connected/queued state, not a permanent blocking
   spinner.
7. Wait for monitoring to move through `QUEUED`/`ANALYZING` to `COMPLETED` and
   confirm the analysis used published GitHub releases rather than tag
   fallback.
8. Compare the preflight counts with the post-connect counts. A connection,
   repository scope, and monitoring row may increase by one; release requests,
   generation runs, model invocations, and content packs must not increase.
9. Repeat the safe callback/repository-selection path once. Namespace, scope,
   binding, and monitoring counts must remain stable.

Stop and disable both global switches if another repository is claimed,
onboarding work is generated, private data reaches logs/traces, or Connected
contradicts provider access. Preserve durable rows for diagnosis.

## 3. Baseline and current release gates

Use a metadata-only temporary checkout outside this repository:

```bash
SMOKE_DIR="$(mktemp -d)"
git clone --filter=blob:none --no-checkout <private-repository-url> "$SMOKE_DIR/repository"
```

Do not write repository files into the Plot workspace. Resolve one existing
released commit as the baseline and one later commit with a customer-relevant
change as the current commit. Create two unique tags, push them separately,
and keep the names only in the restricted local shell:

```text
plot-smoke-<date>-baseline
plot-smoke-<date>-current
```

### Baseline gate

- The tag push reaches the signed production webhook and is accepted.
- Exactly one release request is associated with the baseline tag.
- The resolved head equals the intended baseline commit.
- The request reaches its baseline terminal state.
- No generation run, model invocation, content pack, or exportable artifact is
  created.
- Integrations says the baseline was saved and the next release will prepare a
  changelog. It must not expose `NEEDS_RANGE`, base/head SHAs, or a manual
  range command.

`NO_ACTIVITY` is a valid terminal system result but does not complete this
customer-value certification. If the selected pair has no usable evidence,
stop that pair and choose another existing commit pair; never insert or edit a
release row to force `READY`.

### Current release gate

- Exactly one current release request is created.
- Its resolved base is the stored baseline and its resolved head is the
  intended current commit.
- The GitHub compare range is immutable and matches that boundary.
- Evidence belongs to the approved workspace and repository scope.
- Exactly one generation attempt and one content pack materialize.
- The activity reaches `READY` and the review link opens the matching pack.
- Langfuse observations for the release, generation, and model use opaque IDs
  only. No prompt, completion, repository metadata, source excerpt, raw
  provider error, or stack trace is exported.

### Redelivery gate

Redeliver one safe matching GitHub delivery from the App settings. The current
tag must still have exactly one release request, one generation attempt, one
content pack, and one model invocation set. The stored boundary must not
duplicate or regress. Do not redeliver deletion, suspension, grant-removal, or
other destructive lifecycle events.

## 4. Review, export, and visual gates

Use Computer Use at 1440 by 900 and 390 by 844. Store raw screenshots in a
temporary directory outside tracked paths.

- Open the `READY` activity from Integrations and reach the matching pack.
- Open one citation and verify provider label, source URL, and saved evidence
  correspond to the release. Do not capture the private evidence body.
- Edit one cited factual sentence. The citation must remain visible and its
  text status must become **Unverified** with the saved source and stale reason
  still inspectable.
- Trigger Copy while Unverified. The warning must use `Sentence N` and a
  human-readable excerpt, not an internal UUID. Cancel once and verify no
  export occurs; then acknowledge and verify Copy succeeds.
- Trigger Download and verify equivalent warning semantics and the Markdown
  file. The file must contain no internal UUID, unsafe URL, or private source
  body. Export audit rows may retain sentence IDs in PostgreSQL.
- On desktop, check navigation, repository/release cards, pack/editor, citation
  popover, export dialog, focus rings, borders, shadows, and text baselines for
  clipping or overlap.
- On mobile, check the Integrations-to-Pack-to-Export flow without browser
  Back, horizontal overflow, citation popover width, warning scroll, reachable
  actions, and disabled keyboard/screen-reader semantics.

Inspect screenshots at original resolution after capture. Sanitize away
repository name/URL, account details, UUIDs, and private excerpts before any
Linear attachment. Retake after each visual correction; keep only final raw
candidates until upload verification completes.

## 5. Cleanup, steady state, and rollback

After review/export validation succeeds:

1. Delete only the two explicit `plot-smoke-*` tags.
2. Verify both remote tags are absent and tag-deletion deliveries create no
   release request, generation run, model invocation, or content pack.
3. Remove the metadata-only checkout and downloaded Markdown.
4. Preserve the workspace, repository connection, monitoring row, release
   boundaries, generated pack, revision, delivery audit, citations, and
   immutable evidence.
5. Confirm API health, Vercel production, no stuck/runnable work, enabled AI,
   enabled release automation, privacy-safe Langfuse export, and the latest
   Connected release state.
6. Delete raw local screenshots after sanitized Linear attachments are
   verified. Never upload SQL output, raw traces, GitHub payloads, downloaded
   Markdown, or provider errors.

If any stop condition occurs, disable both global switches and restart or
redeploy the API:

```text
PLOT_AI_ENABLED=false
SPRING_AI_MODEL_CHAT=none
PLOT_GITHUB_RELEASEAUTOMATIONENABLED=false
```

This rollback pauses model and release workers globally for the deployment but
does not delete, edit, or requeue durable rows. Webhook intake may continue to
record signed deliveries. Re-enable only after the failure is understood, the
minimum correction is reviewed and merged, and all affected gates are rerun
against the new merged `main` SHA.

Record for PON-99 only the execution window, deployed `main` SHA, health result,
monitoring terminal state, zero-onboarding-work result, baseline result, READY
result, redelivery idempotency, review/export result, cleanup result,
automation-enabled state, and rollback readiness. Keep all private values and
raw artifacts out of the ticket.
