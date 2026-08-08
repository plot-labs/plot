# GitHub App development smoke test

This runbook validates the backend adapter against a disposable GitHub App and a
non-production repository. It is intentionally manual; automated tests use a
fake `GitHubClient` and never call GitHub.

## Configure the local API

Register an unlisted GitHub App with repository **Contents: read-only**,
**Metadata: read-only**, and **Pull requests: read-only**. Configure the App callback shape used by the
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
plot.github.access-check-poll-delay=5s
plot.github.access-check-lease-timeout=2m
plot.github.access-check-max-attempts=3
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

## Exercise the access lifecycle

Use a disposable installation and repository. Keep the request and delivery
identifiers, but never save the webhook body or a token in a ticket.

1. In the GitHub App settings, subscribe to `Installation`, `Installation
   repositories`, and `Repository` events in addition to Push and Release.
2. Suspend the installation. Redeliver the signed `installation.suspend`
   delivery and confirm HTTP `202`, connection `ERROR`, the typed reason
   `INSTALLATION_SUSPENDED`, and no new release or generation claim.
3. Unsuspend the installation. Confirm the UI remains `Needs attention` while
   the durable access check is `QUEUED`/`CHECKING`, then becomes `Connected`
   only after the provider grant and repository metadata verify.
4. Remove the selected repository grant and redeliver
   `installation_repositories.removed`. Confirm only that scope is inactive,
   its reason is `GRANT_REMOVED`, and the UI offers Retry and Reconnect. Add
   the grant again and verify the same scope ID and release boundary return;
   no release is backfilled for the interruption.
5. Transfer the repository and confirm `REPOSITORY_TRANSFERRED` queues one
   access check. Verify owner, name, URL, and default branch change only after
   a successful check.
6. Delete and restore the repository. Confirm the tombstone is
   `REPOSITORY_DELETED`, no background polling occurs, and Check again restores
   the same identity when GitHub makes it available. Connect another repository
   remains an independent option.
7. Uninstall the GitHub App. Confirm `Disconnected`, revoked bindings, and a
   Reconnect action. Reinstall and select the same repository; retained drafts,
   citations, and the release boundary remain, and the lost-period releases
   are not generated individually.
8. Redeliver every lifecycle delivery once. State, access-check, release, and
   generation counts must remain idempotent. Inspect logs and the UI to confirm
   that raw payloads, credentials, private excerpts, and internal UUIDs are not
   exposed.

The production private-repository smoke is intentionally not part of this
runbook's completion evidence; execute it under PON-99 with production-safe
credentials and a disposable repository.

## Cleanup

Disable the source repository with `DELETE /api/github/repositories/{id}`,
revoke the App installation in GitHub, remove local secrets, and remove any
temporary request/response logs. Provider credentials must never be retained in
the database or application logs.
