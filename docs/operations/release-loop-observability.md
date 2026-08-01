# Release loop observability

This runbook enables Plot's operational traces for the release-to-changelog
loop. It covers webhook intake, durable release and generation attempts, and
Spring AI model observations. Traces are diagnostic only: PostgreSQL remains the
source of truth for deduplication, retries, fencing, and artifact state.

Do not put prompts, completions, repository content, provider response bodies,
credentials, or returned trace payloads in tickets or chat. Correlation IDs are
opaque UUIDs and are safe to use only in the restricted staging/production
observability workspace.

## Configuration boundary

Langfuse Cloud receives standard OpenTelemetry OTLP traces. Keep the endpoint,
Basic authentication value, and deployment environment in the deployment secret
store; never commit them or add them to `.env.example`.

For an isolated staging deployment, provide the equivalent of:

```properties
MANAGEMENT_OPENTELEMETRY_ENABLED=true
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
OTEL_SDK_DISABLED=false
OTEL_TRACES_EXPORTER=otlp
OTEL_TRACES_SAMPLER=always_on
OTEL_EXPORTER_OTLP_ENDPOINT=https://<langfuse-region>/api/public/otel
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64-public-key-colon-secret-key>,x-langfuse-ingestion-version=4
OTEL_RESOURCE_ATTRIBUTES=service.name=plot-api,deployment.environment.name=staging
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false
```

Use `deployment.environment.name=production` for production. Both environments
start at 100% sampling so a release attempt can be inspected end to end. The
application has prompt, completion, and Spring AI error logging disabled; do
not override those settings in a deployment.

Local and test processes must remain network-free for telemetry:

```properties
OTEL_SDK_DISABLED=true
OTEL_TRACES_EXPORTER=none
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

Missing or invalid Langfuse configuration is a telemetry warning, not a reason
to stop webhook intake or durable workers. If export is unavailable, the same
business transition must still be persisted.

## Staging certification

Use a dedicated staging database and a disposable repository. Keep
`plot.github.release-automation-enabled=false` until the connection, webhook,
and model configuration checks pass.

1. Deploy the API with the staging OTLP settings above and release automation
   disabled. Confirm the process starts without credentials in logs and that
   local health checks succeed.
2. Connect the disposable repository and send a signed synthetic release
   webhook. Confirm HTTP `202`, one durable webhook delivery, and no raw body in
   application logs.
3. Exercise a first release with a known boundary. Confirm the release request
   reaches the documented `NEEDS_RANGE` baseline state without invoking the
   model or creating a generation row. Exercise a second release with a small
   customer-facing change and confirm it reaches `READY`, `NO_ACTIVITY`, or the
   documented safe failure.
4. Enable release automation for this isolated deployment, redeliver the same
   webhook, and verify idempotency in PostgreSQL. Keep the resulting
   `webhook_delivery_id`, `release_request_id`, and `generation_run_id` only as
   restricted search values.
5. In Langfuse, search each opaque ID and confirm one webhook observation, one
   observation per claimed release attempt, and one observation per claimed
   generation attempt. A poll that claims no row must not create an attempt
   trace.
6. Open the model child span and confirm provider/model, response ID when
   available, token usage, latency, logical role, and physical attempt. Confirm
   retry attempts have separate traces rather than a parent context loaded from
   PostgreSQL.
7. Inspect the complete trace for the absence of prompts, completions,
   repository owner/name/URL/tag, pull-request content, evidence bodies or
   excerpts, generated sentences, workspace/user identity, raw provider errors,
   and stack traces.
8. Point the staging OTLP endpoint or authentication header at an intentionally
   invalid value. Re-run the same synthetic release and confirm the durable
   release/generation result is unchanged. Restore the secret configuration and
   turn the global release flag off after the exercise.

Record only the staging deployment version, test window, terminal business
outcome, and whether the privacy checks passed. Do not record a secret, full
trace export, source excerpt, prompt, completion, or raw error.

## Searching and incident response

Search Langfuse in this order:

1. `webhook_delivery_id` to find the ingress observation and disposition.
2. `release_request_id` to compare independent release attempts and safe error
   codes.
3. `generation_run_id` to inspect logical model role, physical attempt,
   provider latency, token counts, and nested Spring AI model spans.

If an attempt is stuck, use the existing release and generation SQL queries in
[`github-release-automation.md`](github-release-automation.md) to inspect the
durable claim and heartbeat. Do not use Langfuse as a recovery queue and do not
edit state rows to make a trace look complete.

For a Langfuse timeout, 5xx, authentication failure, or queue saturation:

1. Confirm the business row reached the same `READY`, `NO_ACTIVITY`, retry, or
   safe `FAILED` state in PostgreSQL.
2. Inspect only safe error codes and bounded latency/token fields.
3. Keep release automation enabled only if the business queue is healthy; the
   observability outage itself must not trigger a release retry.
4. If export noise or cost is unexpected, use the rollback below. Do not delete
   durable release requests, generation runs, or content packs.

## Rollback

Disable trace export through deployment configuration:

```properties
MANAGEMENT_OPENTELEMETRY_ENABLED=false
OTEL_SDK_DISABLED=true
OTEL_TRACES_EXPORTER=none
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

Restart or redeploy the API and confirm the release loop still accepts
webhooks, claims durable work, and reaches its normal terminal states. This
rollback changes no table, migration, queue, or artifact data. Re-enable export
only after the endpoint/authentication and privacy checks pass again.

## Linear scope split

PON-97 is limited to AI and durable-worker operational observability:

- webhook, release-attempt, generation-attempt, and model latency/retry/failure
  traces;
- opaque correlation IDs and safe allowlisted metadata;
- privacy and exporter-failure isolation;
- staging certification and rollback.

Create a separate product-analytics issue before implementing any user-action
events. Its Goal should define which behavior proves activation or retention;
its Acceptance should define event semantics, ownership, retention/deletion, and
the privacy/sampling boundary. Do not promise `artifact_opened`,
`citation_reviewed`, activation, or retention event implementation in PON-97 or
in this runbook.

## Validation

From the repository root, run:

```bash
just test-api
just build-api
```

The observability change is complete only when these checks pass and the
staging smoke evidence above is recorded without private trace content.
