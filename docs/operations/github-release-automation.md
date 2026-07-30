# GitHub release automation

This runbook operates Plot's opt-in GitHub release-to-changelog loop. The
automation watches an already connected repository, records ordinary pushes
without generating content, and starts a changelog only when GitHub supplies a
trustworthy release boundary.

Plot prepares a review draft. It never publishes a changelog, creates a GitHub
release, or writes to the repository.

## GitHub App configuration

Grant the App only these repository permissions:

- Metadata: read-only
- Pull requests: read-only
- Contents: read-only

Subscribe to:

- Push
- Release

Set the GitHub App webhook URL to:

```text
https://<api-host>/api/github/webhook
```

Use a dedicated high-entropy webhook secret. Plot requires
`X-Hub-Signature-256` and verifies the HMAC-SHA256 signature before parsing or
persisting a delivery. `X-GitHub-Delivery` is the delivery idempotency key.

The endpoint accepts only `application/json` and rejects oversized bodies using
`plot.github.max-webhook-payload-bytes`. Do not place the App private key,
webhook secret, installation tokens, raw webhook bodies, private source bodies,
prompts, or completions in logs.

## Runtime configuration

Release generation is disabled by default:

```properties
plot.github.release-automation-enabled=false
```

The base GitHub App configuration, webhook secret, and model gateway must also
be valid. Supply all secrets through the deployment secret store:

```properties
plot.github.enabled=true
plot.github.app-id=<app id>
plot.github.app-slug=<app slug>
plot.github.private-key=<PEM private key>
plot.github.state-secret=<state signing secret>
plot.github.webhook-secret=<webhook signing secret>
plot.github.api-base-url=https://api.github.com
plot.github.web-base-url=https://github.com

plot.ai.enabled=true
plot.ai.provider=openrouter
plot.ai.model=openai/gpt-5.4-nano
plot.ai.routing-provider=openai
plot.ai.allow-fallbacks=false
plot.ai.content-logging-enabled=false
spring.ai.model.chat=openai
spring.ai.openai.api-key=${SPRING_AI_OPENAI_API_KEY}
```

`openai/gpt-4o-mini-2024-07-18` is the other currently supported pinned model.
The OpenRouter credential is supplied to the Spring AI OpenAI-compatible
transport with the exact deployment secret name `SPRING_AI_OPENAI_API_KEY`.
The corresponding GitHub deployment secret names are
`PLOT_GITHUB_PRIVATE_KEY`, `PLOT_GITHUB_STATE_SECRET`, and
`PLOT_GITHUB_WEBHOOK_SECRET`. Do not use an OpenAI key for this OpenRouter
endpoint, and do not commit any value.

`plot.github.release-automation-enabled` is a process-wide flag. There is no
workspace or repository allowlist: when true, each worker polls globally and
may claim any runnable release request in that database. Keep the flag false
until the checks below pass. Turning it off is also a global kill switch:
webhook deliveries and release requests continue to be recorded for every
connected scope, but no release worker in that deployment will claim, recover,
reconcile, or generate any draft. Re-enabling can drain all accumulated runnable
requests, not just the repository being investigated.

## Enablement procedure

1. Use an isolated staging database or a dedicated staging deployment with no
   production connections or queued production release requests. The current
   flag cannot safely enable one workspace inside a shared production worker.
2. Deploy the database migration and application with release automation false.
3. Confirm existing GitHub connections are active and their repository scope
   records contain the correct default branch.
4. Confirm the GitHub App has Metadata, Pull requests, and Contents read access
   for the intended repositories.
5. Configure the webhook URL and secret, then use GitHub's redelivery action for
   a disposable event. Expect HTTP `202`.
6. Send an ordinary default-branch push. Confirm it becomes `OBSERVED` and does
   not create a release request or generation run.
7. Verify there are no unintended queued requests in the isolated database,
   then enable release automation for that entire deployment and publish a
   disposable tag. The first trustworthy tag is expected to become
   `NEEDS_RANGE`; it establishes the initial head boundary and must not call
   the model.
8. Publish a second tag containing a known customer-facing change. Confirm the
   exact earlier-head to later-head range, one generation run, one content pack,
   and a `READY` activity visible in the review UI.
9. Redeliver the tag push and the matching `release.published` event. Counts
   must remain one request, one generation attempt, and one content pack for the
   workspace, repository scope, and tag.
10. Turn the global flag off after the staging exercise. A production rollout
    requires an explicit review of every active connection and every queued
    request in the production database.

## Event semantics

| GitHub event | Plot behavior |
| --- | --- |
| Default-branch push | Store as `OBSERVED`; never starts generation. |
| Non-default-branch push | Store as `IGNORED`. |
| Deleted or forced push | Store as `IGNORED`. |
| Tag push | Enqueue one release request per workspace, repository scope, and tag. |
| `release.published` | Enqueue the same logical request as its tag push. |
| Other release actions | Store as `IGNORED`. |

A merge or branch push is not proof that a change is available to customers.
Plot only generates from a later release boundary and always uses resolved
commit SHAs, not a moving branch name.

The first release for a scope has no trusted predecessor. `NEEDS_RANGE` is an
intentional terminal state, not a worker failure. The recorded tag head becomes
a candidate boundary for a later release. Do not hand-edit base/head SHA values
in production. If an exact historical range must be prepared before a second
release exists, leave the global automation flag disabled in that deployment
and use the manual bounded import/generation path; a supported manual
release-range API does not yet exist.

An exact range with no usable commit or pull-request evidence becomes
`NO_ACTIVITY` and must not invoke the model.

## Operational queries

The following examples are executable in `psql`. Set all four values first;
every query deliberately applies all four identity filters:

```sql
\set workspace_id '00000000-0000-0000-0000-000000000000'
\set source_scope_id '00000000-0000-0000-0000-000000000000'
\set installation_id '12345678'
\set repository_id '987654321'
```

Never paste returned source bodies or raw payloads into tickets or chat.

Recent webhook disposition:

```sql
select d.event_type, d.event_action, d.tag_name, d.disposition, d.error_code,
       d.received_at, d.processed_at
from github_webhook_deliveries d
join connections c
  on c.workspace_id = :'workspace_id'::uuid
 and c.provider = 'GITHUB'
 and c.status = 'ACTIVE'
 and c.external_connection_key = :'installation_id'
join connection_namespace_bindings b
  on b.workspace_id = c.workspace_id
 and b.connection_id = c.id
 and b.provider = 'GITHUB'
 and b.status = 'ACTIVE'
join source_scopes s
  on s.workspace_id = b.workspace_id
 and s.source_namespace_id = b.source_namespace_id
 and s.id = :'source_scope_id'::uuid
 and s.external_scope_key = :'repository_id'
 and s.status = 'ACTIVE'
where d.installation_id = :'installation_id'::bigint
  and d.repository_id = :'repository_id'::bigint
order by d.received_at desc
limit 50;
```

Release queue and terminal state:

```sql
select r.id, r.tag_name, r.status, r.attempt_count, r.generation_attempt,
       r.base_sha, r.head_sha, r.boundary_reason, r.error_code,
       r.next_attempt_at, r.heartbeat_at, r.created_at, r.updated_at
from github_release_draft_requests r
join github_webhook_deliveries d on d.id = r.initial_delivery_id
where r.workspace_id = :'workspace_id'::uuid
  and r.source_scope_id = :'source_scope_id'::uuid
  and d.installation_id = :'installation_id'::bigint
  and d.repository_id = :'repository_id'::bigint
order by r.created_at desc;
```

One request, attempt, and pack per release:

```sql
select r.id, r.tag_name, r.status,
       count(distinct a.generation_run_id) as generation_attempts,
       count(distinct p.id) as content_packs
from github_release_draft_requests r
left join github_release_generation_attempts a
  on a.workspace_id = r.workspace_id and a.request_id = r.id
left join content_packs p
  on p.workspace_id = r.workspace_id and p.release_request_id = r.id
join github_webhook_deliveries d on d.id = r.initial_delivery_id
where r.workspace_id = :'workspace_id'::uuid
  and r.source_scope_id = :'source_scope_id'::uuid
  and d.installation_id = :'installation_id'::bigint
  and d.repository_id = :'repository_id'::bigint
group by r.id, r.tag_name, r.status
order by max(r.created_at) desc;
```

Stuck claims:

```sql
select r.id, r.tag_name, r.status, r.claimed_by, r.heartbeat_at,
       r.attempt_count
from github_release_draft_requests r
join github_webhook_deliveries d on d.id = r.initial_delivery_id
where r.workspace_id = :'workspace_id'::uuid
  and r.source_scope_id = :'source_scope_id'::uuid
  and d.installation_id = :'installation_id'::bigint
  and d.repository_id = :'repository_id'::bigint
  and r.claimed_by is not null
  and r.heartbeat_at < now() - interval '2 minutes'
order by r.heartbeat_at;
```

## Test boundary

`GitHubReleaseAutomationIntegrationTest` is a core-worker vertical slice. It
starts from an already parsed webhook object and exercises real scope/range
resolution, evidence persistence, generation persistence/workflow, pack
materialization, reconciliation, and release activity handoff with deterministic
GitHub and model doubles. It does not exercise HTTP body limits, headers, JSON
parsing, or signature verification. Signed HTTP ingress, replay, malformed
signature, required headers, and request-size behavior are covered separately
by the webhook API and verifier integration tests. Malformed JSON/payload
coverage is not currently claimed by this test suite.

## Retry and recovery

The worker retries only safe transient failures, including GitHub rate limits,
network errors, provider unavailability, transient storage failures, and
temporary task-executor rejection. Retries use the same release request and a
bounded backoff. Exhausted attempts and permanent scope, identity, permission,
or evidence errors become `FAILED` with a safe error code.

The periodic worker recovers claims whose heartbeat exceeded the configured
lease timeout. Recovery advances the transition version and requeues the same
request; fencing prevents the stale worker from committing afterward.

For a user-visible `FAILED` activity, use the release activity retry action only
after fixing the cause. A retry keeps the same release request and creates at
most one new, explicitly numbered generation attempt. Do not retry by inserting
database rows.

## Incident response

### GitHub rate limiting or provider outage

1. Confirm the safe error code is `GITHUB_RATE_LIMITED`,
   `GITHUB_NETWORK_ERROR`, or `GITHUB_PROVIDER_UNAVAILABLE`.
2. Check GitHub status and App rate-limit headers outside application logs.
3. Leave queued work in place; bounded retries preserve idempotency.
4. If queue age or request volume threatens the provider, turn off the global
   release automation flag and restart every worker deployment sharing that
   database.
5. Re-enable only after the provider is healthy and queued-request counts are
   understood.

### Permission loss or App uninstall

1. Turn off the global release automation flag if continued processing is
   unsafe; this pauses every repository handled by that deployment.
2. Verify the installation and repository grant in GitHub.
3. Restore only Metadata, Pull requests, and Contents read permissions.
4. Reconnect through the supported GitHub App flow; never copy installation
   tokens into the database.
5. Retry failed activity after scope resolution succeeds. Existing immutable
   evidence and drafts remain inspectable according to workspace retention
   policy, but Plot must not fetch new private content without a valid grant.

### Unexpected model cost or content quality

1. Turn off `plot.github.release-automation-enabled`.
2. Confirm default-branch pushes are only `OBSERVED`.
3. Group counts by scope and tag to find duplicate or unexpected work.
4. Inspect safe model-call counts and durations, not prompts, completions, or
   private evidence bodies.
5. Keep all generated packs in review. Do not publish automatically.

## Disablement

Set:

```properties
plot.github.release-automation-enabled=false
```

Restart or redeploy the API and confirm no release requests are being claimed.
This pauses release processing for every workspace and repository using that
deployment, while new release requests can continue accumulating. Do not delete
queued requests, observations, Writing Blocks, generation runs, or content
packs during an incident. They are part of the durable audit and recovery trail.
Ordinary webhook intake can remain enabled while the generation worker is
disabled.
