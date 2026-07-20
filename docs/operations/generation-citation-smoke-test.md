# Production generation and citation certification

This runbook certifies the fixed generation/citation workflow against the real OpenRouter transport, an approved GitHub import, the production browser path, durable PostgreSQL state, and a process-level API restart. The normal test suite remains offline. Nothing here is a CI default.

The complete command is:

```bash
just generation-certification
```

It is the only sequence that can establish release eligibility. A focused command is diagnostic evidence only and cannot establish GO.

## Roles and stop conditions

- The engineering operator owns the disposable environment, credentials, real GitHub installation/import, cleanup, and final GO/NO-GO input.
- Automation computes eligibility; it never grants release approval.
- Stop before a private source is sent when any origin, listener, route, credential scope, source window, database identity, logging, manifest, or revision check fails.
- `INCONCLUSIVE` means infrastructure prevented a valid score. Preserve it and allocate a replacement attempt at the same scenario and ordinal.
- `HARD_GATE_FAIL` is attributable evidence. A targeted rerun may diagnose it but cannot erase it or establish GO.
- Any code, prompt, schema, corpus, profile, routing, or configuration change starts a new clean revision and a new campaign.

## One-time runner preparation

Install repository dependencies and the pinned Chromium runtime before loading credentials:

```bash
just install
pnpm --filter @plot/web exec playwright install chromium
```

Required local binaries are `git`, `docker`, `curl`, `find`, `jq`, `lsof`, `openssl`, `pnpm`, `printenv`, `shasum`, and Java 21. Docker must resolve through a local Unix socket. The certification script refuses a tunnel process and checks the actual API and web listeners.

## Credential and source preparation

Create two dedicated, revocable credentials:

1. A revocable OpenRouter key accepted by the official `GET /api/v1/key` endpoint. A finite key with zero remaining credit is rejected; unlimited keys and keys with any positive reported balance continue to the synthetic route/structured-output canary, which proves actual model usability before source content is sent.
2. A GitHub App/installation with Metadata read-only and Pull requests read-only for only the approved repository set.

Follow [GitHub App development smoke test](github-app-development-smoke-test.md#production-generation-certification-handoff) for installation and bounded import setup. Prefer approved public or non-sensitive Plot pull requests. Otherwise the data owner must approve the exact repository/PR aliases, bounded fields, and UTC window before the run.

Write that approval before the run to an ignored file such as `$PWD/.generation-certification/source-approval.json`, then set `PLOT_CERTIFICATION_SOURCE_APPROVAL` to its absolute path. The approval is raw transient input and is deleted with local certification material; it never enters the report.

```json
{
  "schemaVersion": "certification-source-approval-v1",
  "approvalId": "approval-<opaque hex>",
  "approvedByOwnerAlias": "owner-<opaque hex>",
  "approvedAt": "<UTC RFC3339 before this execution starts>",
  "sourceAlias": "source-<same opaque hex exported below>",
  "sourceWindowStart": "<same UTC start exported below>",
  "sourceWindowEnd": "<same UTC end exported below>",
  "approvedOriginalUrls": ["https://github.com/<owner>/<repository>/pull/<number>"],
  "approvedModelVisibleFields": [
    "sourceProvider", "sourceKind", "sourceLabel", "title", "body", "excerpt", "sourceCreatedAt", "sourceUpdatedAt"
  ]
}
```

URLs must be exact canonical `https://github.com` URLs without query, fragment, userinfo, or alternate port. Every imported Writing Block used by the run must resolve to one of them.

The approved real-source set is a certification fixture, not an arbitrary healthy PR. Before approval, confirm that the selected blocks contain enough bounded material for all three required product observations: at least two independently supportable release claims, one material conflict that must reach `Needs your call`, and context from which a concise non-factual/evidence-free changelog sentence is expected. If any of these fixture properties is absent, stop and select another approved source set; do not interpret that setup mismatch as a model or product result.

Secrets exist only in the operator shell environment. Never place them in `.env`, command arguments, Playwright, manifests, report input, or files. The orchestrator removes GitHub credentials from model/browser/audit/report subprocesses. OpenRouter credentials are absent from GitHub/browser/report subprocesses; only the live model calls and the isolated audit child receive the key, with the latter using raw response IDs only for in-memory authoritative metadata lookup. Prompt, completion, snapshot, and body-rich diagnostic logging must remain disabled.

## Seal one clean campaign

Commit the implementation before certification. The working tree must be empty, and `PLOT_GENERATION_SOURCE_REVISION` must exactly equal `git rev-parse HEAD`. After the approved GitHub import is scanned, create only the U9 campaign manifest. Automation verifies its imported source-snapshot hash. Each model-execution manifest is generated and sealed later, only after the live canary proves the exact served model and pinned upstream.

The model order is fixed:

1. `openai/gpt-5.4-nano`
2. `openai/gpt-4o-mini-2024-07-18`

Nano is selected when all of its release gates pass. 4o Mini is eligible only as the fallback when Nano fails and 4o Mini passes. Both candidates still require three valid attributable attempts. GPT-5-family calls omit unsupported temperature; the 4o Mini profile retains its role-specific temperature. Each execution records a profile hash and route-policy hash.

Set safe identity and path variables. Values below are shapes, not reusable values:

```bash
export PLOT_GENERATION_CERTIFICATION_RUN=true
export PLOT_GENERATION_SOURCE_REVISION='<40-hex committed revision>'
export PLOT_CERTIFICATION_CAMPAIGN_ID='campaign-<opaque hex>'
export PLOT_CERTIFICATION_CAMPAIGN_MANIFEST='<absolute ignored path>'
export PLOT_CERTIFICATION_OUTPUT_ROOT="$PWD/apps/api/build/certification"
export PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT="$PWD/apps/web/test-results/certification"
export PLOT_CERTIFICATION_REPORT_OUTPUT="$PWD/docs/operations/certifications/<date>-<source-sha>.md"

export PLOT_CERTIFICATION_NANO_MODEL='openai/gpt-5.4-nano'
export PLOT_CERTIFICATION_MINI_MODEL='openai/gpt-4o-mini-2024-07-18'

export PLOT_CERTIFICATION_CORPUS_HASH='sha256:<64-hex>'
export PLOT_CERTIFICATION_SOURCE_ALIAS='source-<opaque hex>'
export PLOT_CERTIFICATION_SOURCE_OWNER_APPROVED=true
export PLOT_CERTIFICATION_SOURCE_APPROVAL="$PWD/.generation-certification/source-approval.json"
export PLOT_CERTIFICATION_SOURCE_WINDOW_START='<UTC RFC3339>'
export PLOT_CERTIFICATION_SOURCE_WINDOW_END='<UTC RFC3339>'

export PLOT_CERTIFICATION_GITHUB_IMPORT_APPROVED=true
```

Do not predeclare the environment fingerprint, profile hashes, `PLOT_CERTIFICATION_PROFILE_OUTPUT`, source-snapshot hash, Writing Block IDs, workspace ID, restart request, operator-decision paths, browser attempt IDs, or observation paths. Automation hashes the exact loopback origins, canonical external origins, database image/fingerprint, models, routing provider, timeout/token/retry profile, and claim timeout into `PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT`; the campaign seal rejects any other value. After the import handoff, automation derives the source hash and exact request-profile hashes, fixes the isolated workspace, and creates all ignored handoff paths under the certification output root. The orchestrator derives the valid U3 attempt identity at each model/ordinal, reuses it for the first real GitHub journey, and resolves the immutable browser envelope by its sealed identity. If browser infrastructure prevents a score, it runs a fresh full model-corpus attempt for the same model and ordinal before retrying the journey; the two independent lineages remain visible. Raw run/workspace IDs remain transient and are never rendered into the report.

The restart model is also not an operator choice. After both real-source matrices reconcile, automation applies Nano-first cost selection (Mini only when Nano is ineligible and Mini passes) and derives a distinct `process-restart` attempt from the sealed execution hash.

After the approved GitHub import handoff is read, a typed builder resolves the one active source scope shared by the new Writing Block IDs and creates the ignored restart request with `CREATE_NEW` semantics. Its fixed contract is:

```json
{
  "instruction": "Generate a concise changelog grounded only in the selected evidence.",
  "writingBlockIds": ["<approved UUID>"],
  "sourceScopeId": "<derived UUID>"
}
```

It is deleted after the restart gate. Do not include source bodies, titles, URLs, tokens, or free-form diagnostics.

When prompted after the live matrix, prepare the draft decision. It is always `NO_GO` because cleanup has not happened:

```json
{
  "operatorAlias": "operator-<opaque hex>",
  "decidedAt": "<UTC RFC3339>",
  "requestedDecision": "NO_GO",
  "rubric": {
    "factualUsefulness": 1,
    "changelogClarity": 1,
    "citationPlacement": 1,
    "hedging": "APPROPRIATE"
  }
}
```

After listeners stop, credentials are revoked, and the state secret is disposed, write the prompted cleanup-attestation file. Do not set cleanup booleans directly in the shell:

```json
{
  "attestedAt": "<UTC RFC3339 at or after this execution started>",
  "attestedByOperatorAlias": "operator-<same alias as the draft decision>",
  "campaignId": "campaign-<this campaign>",
  "campaignManifestHash": "sha256:<this sealed campaign hash>",
  "githubCredentialRevoked": true,
  "openRouterCredentialRevoked": true,
  "sourceRevision": "<40-hex committed revision>",
  "stateSecretDisposed": true
}
```

The orchestrator exact-checks the identity and time, purges the raw API/browser roots, measures listener and Docker disposition, and writes the cleanup result into a separate safe root. When prompted after cleanup, create the final decision file with the same exact fields as the draft decision, an updated `decidedAt` at or after cleanup, and `GO` or `NO_GO`. Rubric values are integers 1–5; `hedging` is `TOO_LITTLE`, `APPROPRIATE`, or `TOO_MUCH`. Unknown fields and free-form notes are rejected.

The GitHub-only phase reads these dedicated secret variables from the operator shell:

```bash
export PLOT_GITHUB_APP_ID='<dedicated app id>'
export PLOT_GITHUB_APP_SLUG='<dedicated app slug>'
export PLOT_GITHUB_PRIVATE_KEY='<dedicated PEM value>'
export PLOT_GITHUB_STATE_SECRET='<ephemeral high-entropy value>'
export OPENROUTER_API_KEY='<dedicated revocable key>'
export PLOT_AI_ROUTING_PROVIDER='<approved OpenRouter provider slug>'
```

The cleanup result attributes external revocation assertions to the draft/final operator alias and attestation time; listener, local-artifact, and database disposition fields are measured by the orchestrator and cannot be supplied as a PASS JSON.

## Disposable stack and fail-closed preflight

The orchestrator creates a digest-pinned PostgreSQL container published only on `127.0.0.1`, and it owns the container plus the API and Next process IDs. Use a fresh name and one-time fingerprint token:

```bash
export PLOT_CERTIFICATION_DATABASE_NAME='plot_cert_<opaque hex>'
export PLOT_CERTIFICATION_DATABASE_PORT=55432
export PLOT_CERTIFICATION_DATABASE_PASSWORD='<ephemeral random value>'
export PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN='<ephemeral random value>'
export PLOT_CERTIFICATION_POSTGRES_IMAGE='postgres@sha256:<approved digest>'
export PLOT_CERTIFICATION_API_PORT=8080
export PLOT_CERTIFICATION_WEB_PORT=3000
export PLOT_CERTIFICATION_API_ORIGIN='http://127.0.0.1:8080'
export PLOT_CERTIFICATION_WEB_ORIGIN='http://127.0.0.1:3000'
```

The orchestrator derives `PLOT_CERTIFICATION_DATABASE_FINGERPRINT` as SHA-256 of the exact UTF-8 bytes `databaseName + "\n" + disposableToken`. If an existing fingerprint is present, it must match; operators do not calculate or copy this value manually.

Preflight verifies:

- the source revision and clean report path;
- real loopback-only API and Next listeners, rejection of external `Host` and `X-Forwarded-Host`, and absence of tunnel/reverse-proxy processes;
- canonical `https://openrouter.ai/api/v1`, `https://api.github.com`, and `https://github.com` origins with redirects/userinfo/alternate ports rejected by the client contracts;
- a fresh `plot_cert_*` database fingerprint and empty certification-owned baseline before fixture/import work;
- ignored writable artifact roots, disabled development bootstrap, disabled content logging, retry boundaries, exact model profiles, corpus/profile hashes, and required binaries;
- dedicated credentials without printing them or inheriting them into unrelated subprocesses;
- a pre-run, exact-field source-owner approval whose canonical URL allow-list, alias, and UTC window match the imported Writing Blocks, followed by the local sensitive-data scan;
- one structured-output-capable pinned route per model, followed by a non-sensitive production-transport canary and authoritative generation-metadata attribution. ZDR and OpenRouter's `require_parameters` filter are currently disabled; their effective values remain sealed in the model profile and route-policy hash, while structured output is still required by inventory and parser validation.

Run the static, no-network preflight when diagnosing revision, path, origin, database identity, and operator input setup:

```bash
just generation-certification-preflight
```

This command never starts a listener or sends PR content. To diagnose one model route separately with a synthetic canary, set `PLOT_GENERATION_CERTIFICATION_PREFLIGHT=true`, `PLOT_AI_MODEL`, `PLOT_AI_ROUTING_PROVIDER`, and `OPENROUTER_API_KEY`, then run `just generation-certification-model-route-preflight`.

## Ordered certification sequence

`just generation-certification` executes these stages in order:

1. Disposable PostgreSQL startup, certification-only Flyway migration, and minimal principal bootstrap.
2. A GitHub-only API process and same-revision production Next build/listener. The operator completes the approved installation/import and confirms the exact Writing Block set. OpenRouter credentials are absent in this phase.
3. Sensitive-source preflight and campaign sealing against the imported snapshot set.
4. Offline deterministic API, web, API-client, artifact-isolation, export-warning, prompt-injection, active-content, conflict, stale-edit, rewrite-exhaustion, and same-process recovery gates, recorded against that campaign/revision.
5. Per-model OpenRouter inventory, pinned-route, canary, and metadata preflight, followed by model-manifest sealing and the two-model contract corpus until each model has three valid attempts. Each case composes the actual writer output into the reviewer, rewrites every actual `NEEDS_SUPPORT` sentence, and re-reviews that rewritten output; the fixed oracle corpus remains a separate deterministic scoring gate. Every per-case outcome and inconclusive lineage remains immutable.
6. A fresh API process per model execution. Serial Chromium runs one real-source observation for each ordinal with Playwright retries disabled. An infrastructure-inconclusive observation remains immutable and triggers a fresh full model-corpus attempt plus same-ordinal browser replacement, bounded to four browser observations per slot. The model replacement emits an exact-field result bound to the prior browser attempt, selected replacement attempt, model execution, and ordinal; reconciliation retains its SHA-256 hash and rejects a replacement without that causal proof. A hard contract failure is terminal and is never retried into eligibility. GitHub/OpenRouter secrets, traces, screenshots, video, HTML reports, storage state, and body-rich diagnostics are absent.
7. For every browser observation that reached the product contract, the audit extractor reads production tables directly with an allow-list. It records safe invocation counts, sentence verdict/origin counts, citation statuses, human-decision counts, and the exact zero/one/two-event export sequence. A pre-product browser infrastructure failure is reconciled offline without inventing durable database evidence.
8. The offline reconciler joins campaign hash, model-execution hash, attempt, scenario, ordinal, source-snapshot-set hash, browser state, and durable audit when applicable. Model-attempt replacement and browser-attempt replacement are separate, cycle-safe lineages. Browser `OBSERVED` remains `INCONCLUSIVE/PENDING_AUDIT_RECONCILIATION` until this step returns `PASS`.
9. A certification-only API launcher pauses after a committed durable checkpoint. The orchestrator records the marker, stops the real API process, keeps PostgreSQL, waits the validated claim-timeout envelope, restarts the same model execution, and reconciles no duplicate model, citation, intervention, content-pack, or export effect.
10. The assembler reads sealed manifests, U3 per-case envelopes, browser/audit reconciliations, deterministic evidence, and restart evidence; it derives the cost-first model, attempt aggregates, and a redacted draft. A hash-bound, redacted report-input snapshot is copied into a separate safe root. There is no operator-authored metrics/report JSON.
11. The operator revokes/disposes credentials and state secret. The orchestrator stops owned listener trees and the database, purges the entire raw API and browser roots before evaluating artifact deletion, then emits an identity-bound cleanup envelope beside the safe snapshot.
12. Only after cleanup `PASS`, the operator records a final decision timestamp. The final renderer accepts only the hash-verified safe snapshot, cleanup envelope, and exact operator decision, writes the committed FINAL report, and then purges the safe root. A safe-root purge failure removes the report and keeps the run failed.

The restart trigger request is created automatically after import and contains only the fixed instruction, approved Writing Block IDs, and derived source-scope ID. Its body is never placed on a command line and is deleted after the restart gate.

## Focused diagnostic commands

These commands do not establish release eligibility:

```bash
just generation-certification-deterministic
just generation-certification-browser
just generation-certification-audit
just generation-certification-reconcile
just generation-certification-report
just generation-certification-cleanup
just generation-contract-smoke
```

`generation-certification-audit`, `-reconcile`, `-report`, and `-cleanup` require `PLOT_GENERATION_CERTIFICATION_TOOL=true` plus their documented absolute input/output paths. Writers use create-new semantics and reject symlinked or duplicate artifacts.

## Export audit contract

Certification accepts only these durable Markdown export sequences:

- zero events;
- one attributable `REJECTED` warning event;
- one attributable `SUCCEEDED` event; or
- exactly `REJECTED` then `SUCCEEDED`, with strictly increasing timestamps, the same user/variant/disposition/unresolved count, `EXPORT_CONFIRMATION_REQUIRED` only on the rejection, explicit acknowledgement on unresolved success, and an output hash only on success.

Duplicate successes, equal timestamps, reordered events, mismatched actors or variants, and more than two events fail reconciliation. Repeating a successful confirmation is idempotent and does not add another success.

## Report and human decision

The report schema and privacy rules are in [certifications/README](certifications/README.md). The renderer preserves every hard failure and inconclusive replacement, native prompt/completion/reasoning/cached token counts, actual cost, cold/warm latency, rewrite/model-call counts, contract basis-point metrics, exact served model, pinned upstream, route-policy/profile hashes, browser/audit result, process-restart counts, cleanup booleans, and enumerated operator rubric. Model-call counts are physical provider exchanges; a successful logical call that required a transport or schema retry is not eligible for the attributable baseline because the failed exchange has no independently retrievable response metadata.

The operator supplies only an opaque operator alias, UTC timestamp, numeric rubric, hedging enum, and `GO` or `NO_GO`; token, cost, latency, quality, attempt, and gate fields are assembled from immutable evidence. The final decision timestamp must be at or after the identity-bound cleanup observation. GO is rejected unless the cost-first selected model has three valid passing ordinals and matching browser/audit results, deterministic and process-restart gates pass, and cleanup passes. A nonselected candidate's failure remains visible and cannot be reclassified.

## Cleanup and retention

Before final GO:

- stop API and Next listeners;
- revoke the GitHub and OpenRouter credentials and dispose of the state secret;
- create the redacted, hash-bound safe report snapshot, then delete the complete raw API and browser roots before cleanup evaluation; retain only that safe snapshot, cleanup result, and final decision until FINAL rendering, and delete the safe root immediately afterward;
- destroy the disposable PostgreSQL container/volume, or transfer it to encrypted access-restricted retention with an opaque owner alias and future expiry;
- run `just generation-certification-cleanup` and require `PASS`;
- run the redaction validator again against final Markdown.

A cleanup failure keeps the decision `NO_GO`, even when every earlier gate passed.

The shell-level isolation regression can be run without credentials:

```bash
./scripts/test-generation-certification-environment.sh
```

## Short model-route-only check

For a pinned-route, structured-output, and metadata diagnostic without GitHub, browser, corpus scoring, persisted audit, process restart, or release eligibility:

```bash
export PLOT_GENERATION_CERTIFICATION_PREFLIGHT=true
export OPENROUTER_API_KEY='<ephemeral key>'
export PLOT_AI_ROUTING_PROVIDER='<approved provider slug>'
export PLOT_AI_MODEL='openai/gpt-5.4-nano'
just generation-certification-model-route-preflight
```

Repeat with `openai/gpt-4o-mini-2024-07-18` when needed. This is not a production certification and cannot produce GO. The full `generation-contract-smoke` intentionally requires an already sealed campaign and is invoked by the top-level orchestrator.
