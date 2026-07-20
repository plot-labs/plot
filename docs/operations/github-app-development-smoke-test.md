# GitHub App development smoke test

This runbook validates the backend adapter against a disposable GitHub App and a
non-production repository. It is intentionally manual; automated tests use a
fake `GitHubClient` and never call GitHub.

## Production generation certification handoff

For the production generation/citation campaign, this runbook supplies only the GitHub installation and bounded import phase of the complete [production generation certification](generation-citation-smoke-test.md). Use a dedicated, revocable App installation restricted to the data-owner-approved repository aliases and UTC PR window. Complete installation/import while the orchestrator is running its GitHub-only API phase; OpenRouter credentials must be absent from that process.

Record only opaque source aliases, Writing Block UUIDs in transient operator environment, the approval window, and import counters. Do not place repository names, PR titles/URLs/bodies, installation IDs, callback state, or tokens in manifests, browser output, audit envelopes, or reports. After confirming the exact imported block set, the orchestrator restarts the API without GitHub credentials for model/browser execution.

The certification cleanup gate requires the App/installation credential to be revoked or disposed. The development cleanup instructions below are mandatory, not optional, for final GO.

## Configure the local API

Register an unlisted GitHub App with repository **Metadata: read-only** and
**Pull requests: read-only**. Configure the App callback shape used by the
frontend handoff, then provide secrets only through the local process
environment/configuration:

```properties
plot.github.enabled=true
plot.github.dev-only=true
plot.github.loopback-only=true
plot.github.app-id=<app id>
plot.github.app-slug=<app slug>
plot.github.private-key=<PEM private key>
plot.github.state-secret=<random 32-byte secret>
plot.github.api-base-url=https://api.github.com
plot.github.web-base-url=https://github.com
plot.github.import-page-cap=20
```

Keep the API bound to loopback while `dev-only=true`. Do not commit the PEM,
state secret, installation token, or callback URL containing `state`.
Run the API with an explicit local profile, for example
`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`.

## Exercise the flow

1. `POST /api/github/installations/requests` and open the returned `installUrl`
   in a browser. Install the App only into the disposable repository owner.
2. Submit the returned `state` and GitHub `installation_id` to
   `POST /api/github/installations/callback`. Confirm that the response lists
   every granted repository and no token or workspace ID.
3. `PUT /api/github/repositories/{externalRepositoryId}` with the callback's
   `connectionId` to activate one repository. Repeat for a second repository to
   prove that multiple source containers coexist.
4. `POST /api/github/repositories/{sourceScopeId}/imports` with a bounded
   UTC interval containing a known merged pull request. Confirm `COMPLETED`,
   `eligibleCount`, and the created/updated/unchanged counters.
5. Repeat the same import and verify that the Writing Block ID is unchanged and
   the second import has its own lifecycle record.
6. Read `/api/blocks?sourceScopeId={sourceScopeId}` and confirm the
   imported block is present. Attempting `PATCH /api/blocks/{id}` must return
   `SOURCE_MANAGED`.
7. Submit the same callback state again. It must return `INVALID_GITHUB_STATE`
   and must not cause another GitHub token exchange.

## Cleanup

Disable the source repository with `DELETE /api/github/repositories/{id}`,
revoke the App installation in GitHub, remove local secrets, and remove any
temporary request/response logs. Provider credentials must never be retained in
the database or application logs.
