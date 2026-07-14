# Generation and citation smoke test

This runbook validates the optional OpenAI structured-output contract and the
local GitHub evidence-to-export path. Normal tests stay hermetic: they use fake
model transports and never require an API key or external network.

## Safety and data handling

- Use a disposable repository and non-sensitive fixtures. Generation-time
  snapshots are persisted workspace data even if the connector record later
  changes or becomes unreadable.
- Source text is untrusted prompt data. The model receives no tools, connector
  credentials, or workspace secrets, and provider-supplied URLs are discarded.
- Prompt, completion, snapshot, and exported text logging must remain disabled.
  The contract smoke prints only model/version, call/token/latency counts, cost
  metadata, and aggregate outcome metrics.
- Confirm the selected provider's retention, training, residency, and abuse
  monitoring terms before sending production workspace data.

## Run the opt-in model contract

The corpus at
`apps/api/src/test/resources/evals/generation-citation-cases.json` covers
supported and unsupported claims, non-factual copy, multiple citations,
conflicts, numbers, dates, prompt injection, and targeted partial rewrites.

Set credentials only in the process environment:

```bash
export PLOT_AI_CONTRACT_SMOKE=true
export OPENAI_API_KEY='<ephemeral key>'
export PLOT_AI_MODEL='<structured-output-capable model>'

# Optional OpenAI-compatible endpoint and reporting metadata:
export PLOT_AI_BASE_URL='https://api.openai.com/v1'
export PLOT_AI_TIMEOUT_SECONDS=45
export PLOT_AI_MAX_OUTPUT_TOKENS=2000
export PLOT_AI_COST_PER_1M_TOKENS_USD='<blended reporting estimate>'

just generation-contract-smoke
```

The command fails before Gradle starts when the opt-in flag, API key, or model
is absent. The test validates writer, reviewer, and targeted-rewrite payloads
against the same schemas and semantic validators used by the API. It reports
unsupported-claim recall, supported-claim recall, citation precision, citation recall, conflict recall,
`NOT_REQUIRED` false-positive rate, calls, tokens, latency, and optional cost.
Do not copy provider responses into issues or CI logs.

## Configure the local API

Generation is disabled by default. Enable it explicitly alongside the GitHub
development adapter:

```properties
plot.ai.enabled=true
plot.ai.provider=openai
plot.ai.model=<same validated model>
plot.ai.timeout=45s
plot.ai.transport-retries=1
plot.ai.schema-retries=1
plot.ai.max-model-calls=12
plot.ai.max-total-tokens=80000
plot.ai.max-run-duration=5m
spring.ai.model.chat=openai
spring.ai.openai.api-key=<ephemeral key>
```

Keep prompt/completion observation logging false. Follow
[GitHub App development smoke test](github-app-development-smoke-test.md) to
install the disposable App, activate one repository, import a bounded window,
and obtain `sourceScopeId` and Writing Block IDs.

Retry ownership is deliberately separate:

- the provider transport retries transient network/provider failures;
- the structured gateway retries schema conversion failures;
- the workflow rewrites only sentences that fail semantic grounding review.

None of these loops is unbounded, and a conflict pauses as `NEEDS_YOUR_CALL`
instead of guessing a final sentence.

## Exercise GitHub evidence through export

1. Create a generation with a unique idempotency key:

   ```http
   POST /api/generations
   Idempotency-Key: smoke-<unique value>
   Content-Type: application/json

   {
     "sourceScopeId": "<source scope UUID>",
     "writingBlockIds": ["<Writing Block UUID>"],
     "instruction": "Write a conservative customer-facing changelog."
   }
   ```

2. Poll the returned `Location` while status is `QUEUED`, `WRITING`,
   `REVIEWING`, or `REWRITING`. Verify every `SUPPORTED` sentence has one or
   more server-derived evidence IDs, source labels, snapshot excerpts, and
   original links. A genuinely non-factual sentence may be `NOT_REQUIRED`
   without a citation.
3. Import two disposable source records that disagree on rollout scope and
   generate from both. Verify status becomes `NEEDS_YOUR_CALL`; submit one of
   `PREFER_SOURCE`, `OMIT_CLAIM`, or `PROVIDE_WORDING` to
   `/api/generations/{runId}/interventions/{interventionId}/resolution` with
   the returned `expectedVersion`. Confirm only the targeted sentence is
   rewritten and polling resumes.
4. Patch one sentence through
   `/api/content-variants/{variantId}/sentences/{sentenceId}` using its
   `expectedRevisionNumber`. Verify that sentence alone becomes
   `USER_MODIFIED`, loses confirmed citation display, and remains readable.
5. Export through `/api/content-variants/{variantId}/exports`. An unresolved
   variant must first return `EXPORT_CONFIRMATION_REQUIRED`; repeat with
   `acknowledgeUnresolved: true` only after reviewing the affected sentence
   IDs. Test both `COPY` and `DOWNLOAD` dispositions.
6. Compare surfaces: Plot's inline citation popover may show the persisted
   snapshot excerpt and original-link action. Exported Markdown must contain
   only source names and original links—never snapshot text, content hashes,
   connector credentials, or internal IDs.
7. Restart the API during an in-progress disposable run. Verify recovery
   resumes from the durable checkpoint without duplicating citations,
   interventions, or export events.

## Cleanup

Revoke the disposable GitHub App installation, remove provider credentials,
delete temporary logs/screenshots that contain source text, and purge the
disposable workspace data according to the local environment's retention
policy. The product does not yet expose a retention/purge administration UI.
