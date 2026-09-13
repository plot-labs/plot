# WorkOS authentication operations

This runbook describes the production ownership boundary for Plot account
authentication. WorkOS owns account credentials, email verification, password
reset, account OAuth, AuthKit sessions, access-token refresh, and signing-key
rotation. Plot owns the local product projection, Workspace authorization,
entitlements, billing, and GitHub repository integration.

Plot's browser routes use the WorkOS AuthKit SDK and custom UI:

- `/api/auth/sign-in` and `/api/auth/sign-up` create the hosted WorkOS entry
  URL when a redirect is appropriate.
- `/api/auth/sign-in/password` and `/api/auth/sign-up/password` call WorkOS
  User Management from the server. They do not write a Plot password hash.
- `/api/auth/verify-email`, `/api/auth/verify-email/resend`, and
  `/api/auth/reset-password` use WorkOS verification and password-reset APIs.
- `/auth/callback` completes the AuthKit session and `/api/auth/sign-out`
  clears it.

The Web BFF reads the server-side AuthKit session, selects the short-lived
WorkOS access token, and sends only `Authorization: Bearer ...` to the Kotlin
API. The API never receives the browser session cookie and never mints an
account JWT.

## Ownership and configuration

Keep configuration in the environment that owns the capability. Do not put
secret values in browser-exposed or checked-in configuration.

| Owner | Configuration | Purpose |
| --- | --- | --- |
| Web/AuthKit | `WORKOS_API_KEY`, `WORKOS_CLIENT_ID`, `WORKOS_COOKIE_PASSWORD`, `WORKOS_COOKIE_NAME`, `WORKOS_COOKIE_SAMESITE` | Server-side AuthKit session and WorkOS UI helpers |
| Web/origin | `NEXT_PUBLIC_WORKOS_REDIRECT_URI`, `PLOT_APP_ORIGIN`, `PLOT_WORKOS_ALLOWED_ORIGINS`, `PLOT_WORKOS_SESSION_COOKIE_NAME` | Callback and CSRF/origin policy; only the redirect URI is public |
| API/verification | `PLOT_WORKOS_ENABLED`, `PLOT_WORKOS_API_KEY`, `PLOT_WORKOS_CLIENT_ID`, `PLOT_WORKOS_ISSUER`, `PLOT_WORKOS_AUDIENCE`, `PLOT_WORKOS_JWKS_URI` | JWT verification and WorkOS SDK calls |
| API/membership | `PLOT_WORKOS_WEBHOOK_SECRET`, `PLOT_WORKOS_OWNER_ROLE_SLUG`, `PLOT_WORKOS_BOOTSTRAP_ENABLED` | Membership events, owner-role normalization, and bootstrap kill switch |
| GitHub integration | `PLOT_GITHUB_PRODUCTOAUTHCLIENTID`, `PLOT_GITHUB_PRODUCTOAUTHCLIENTSECRET`, `PLOT_GITHUB_PRODUCTOAUTHREDIRECTURI`, `PLOT_GITHUB_PRODUCTCREDENTIALENCRYPTIONKEY`, and its version | Product GitHub OAuth and encrypted repository credentials |

Configure the WorkOS project with the AuthKit callback used by the Web app and
the allowed application origin. Enable the email/password and GitHub account
methods required by the product. WorkOS GitHub account login establishes
identity only; it does not grant Plot repository access. Repository access is
requested separately through the product GitHub OAuth callback
`/api/plot/github/oauth/callback`, with the scopes recorded in the GitHub
integration configuration.

When the API is enabled, `PLOT_WORKOS_ISSUER`, `PLOT_WORKOS_AUDIENCE`, and
`PLOT_WORKOS_JWKS_URI` must be copied from the WorkOS access-token configuration
for the same environment. Do not infer these values from the web origin.

## Token and session behavior

The Kotlin API validates issuer, audience, signature, expiry, and subject with
the configured WorkOS JWKS endpoint. The subject must resolve through
`workos_identity_mappings` to one active Plot User. Scoped requests also require
the active WorkOS Organization claim (`org_id`), a supported role claim, the
mapped Plot Workspace, and an active `workspace_members` projection.

WorkOS access-token claims such as `org_id` and `role` are provider context, not
Plot billing or artifact permissions. Plot continues to evaluate product-level
authorization after the membership boundary. See the WorkOS references for
[session and access-token claims](https://workos.com/docs/authkit/sessions) and
[roles and permissions](https://workos.com/docs/authkit/roles-and-permissions).

If a session expires, AuthKit refreshes it at the Web boundary. If refresh is
terminal, the user is redirected to sign-in. There is no local session lookup,
password hash comparison, Plot JWT signing, or Plot JWKS endpoint to fall back
to. A database rollback cannot roll back a WorkOS Organization or membership;
recover those through the provisioning ledger and reconciliation flow.

For signing-key rotation:

1. Confirm the WorkOS issuer, audience, and JWKS URI in the deployment secret
   inventory before the rotation window.
2. Verify that the API can accept a token signed by the new key while the old
   key is still published.
3. Exercise `/api/me`, Workspace switching, and one scoped read after the
   provider rotation.
4. If validation fails, restore the verified WorkOS JWKS configuration and
   reauthenticate affected sessions. Do not create a local signing key.

## Membership webhook processing

Register the WorkOS membership event endpoint as:

```text
https://<api-origin>/api/workos/webhook
```

The endpoint accepts the provider `WorkOS-Signature` header and verifies the
raw body with `PLOT_WORKOS_WEBHOOK_SECRET` before parsing it. Keep the endpoint
publicly reachable, but do not bypass signature verification.

Supported events are organization membership created, updated, and deleted.
Each event is recorded in `workos_membership_event_inbox` with its provider
event ID and SHA-256 payload hash. A repeated event ID with the same payload is
acknowledged as `DUPLICATE`; the same ID with a different payload is rejected.
Provider state is re-fetched before projection so a delayed grant cannot
restore a later revoke or downgrade. Provider failures are stored as `RETRY`
with a bounded backoff; invalid or repeatedly failing events become
`DEAD_LETTER`. Inspect the inbox before asking WorkOS to redeliver an event.

Useful checks:

```sql
select event_id, event_type, state, attempt_count, next_attempt_at, last_error
from workos_membership_event_inbox
order by received_at desc
limit 100;
```

For signature and delivery guidance, use the official [WorkOS webhook
documentation](https://workos.com/docs/events/data-syncing/webhooks). Never log
the webhook secret, raw body, access token, refresh token, or full provider
payload.

## Account bootstrap and recovery

After a verified WorkOS session, the Web app calls `POST /api/account/bootstrap`.
The API creates or resumes one WorkOS Organization, one Plot Personal
Workspace, one owner membership, and one identity mapping through the
idempotent provisioning ledger. `POST /api/me` is not used; `GET /api/me`
refreshes the active user's membership projection and returns the Workspace
list.

For a failed bootstrap, inspect the WorkOS provisioning ledger and provider
state, then retry the same user flow. Use
`PLOT_WORKOS_BOOTSTRAP_ENABLED=false` as an emergency kill switch when the
provider is unavailable or reconciliation requires a pause. Never create a
second Plot User by matching only an email address.

## GitHub credential migration

Product GitHub credentials are not account credentials. The operator-triggered
backfill reads only legacy GitHub rows, joins them to the existing immutable
identity mapping key, encrypts access/refresh material into
`github_product_credentials`, and advances a checkpoint. It does not migrate
passwords, local sessions, or account-auth-only fields. Unmappable, conflicting,
missing-scope, and invalid rows go to
`github_product_credential_quarantine`; raw token material must not appear in
the report or logs.

Run the backfill in bounded batches using the application service or the
approved operator entry point for the deployment. Verify:

```sql
select state, last_source_id, processed_count, migrated_count,
       quarantined_count, last_error
from github_product_credential_backfill
where id = 1;

select reason, count(*)
from github_product_credential_quarantine
group by reason
order by reason;

select status, count(*)
from github_product_credentials
group by status;
```

After verification, the user can explicitly reconnect GitHub from Integrations
to replace or retire a credential. WorkOS GitHub account login alone must never
be treated as an active repository credential.

## Cutover checklist

### Pre-cutover

- [ ] WorkOS AuthKit callback and allowed origins are configured for the exact
      Web origin.
- [ ] API issuer, audience, JWKS URI, API key, and webhook secret are present
      in the runtime secret store; values are not in browser code or logs.
- [ ] Email verification, password reset, sign-in, sign-up, callback, refresh,
      and sign-out are tested through the Plot UI.
- [ ] WorkOS GitHub account login and product GitHub repository authorization
      are tested as two separate flows.
- [ ] Membership webhook deliveries are signed and reach `/api/workos/webhook`.
- [ ] `workos_identity_mappings`, organization mappings, active memberships,
      and provisioning ledger rows have no unexpected or orphaned records.
- [ ] Product GitHub backfill has a checkpoint, a dry-run/report, and an owner
      for each quarantine reason.
- [ ] API/Web lint, tests, build, migration, and generated-schema checks pass.

### Post-cutover

- [ ] No active application path writes or reads local password, session, Plot
      JWT, or Plot JWKS state.
- [ ] A new verified user receives exactly one mapped Plot User, Personal
      Workspace, Organization, and owner membership after retrying bootstrap.
- [ ] A second Workspace switches only after the WorkOS Organization session is
      refreshed; a mismatched Workspace header is rejected.
- [ ] A removed membership becomes inaccessible after the accepted provider
      state reaches the local projection.
- [ ] GitHub installation sync, repository access checks, imports, routines,
      and reauthorization still use product-owned credentials.
- [ ] Billing webhook resolution still updates the intended Workspace.
- [ ] Provider outage, invalid token, JWKS rotation, webhook replay, and
      terminal refresh failure have bounded recovery behavior.

## Legacy data retention and deletion decision

V5, V34, and V35 Flyway migrations are immutable historical references. The
cutover stops writes and removes active runtime dependencies, but it does not
silently drop legacy tables or data.

The data owner and security owner must separately decide retention and deletion
for:

- legacy password hashes and account records;
- legacy refresh/session material;
- legacy Plot signing keys and public JWKS rows;
- quarantined product GitHub credentials and their audit metadata.

Until that decision is recorded, restrict access to the legacy tables, retain
the quarantine and backfill audit rows, and do not copy raw credentials into
tickets, logs, screenshots, or local fixtures. Any archive or drop migration
requires a separately approved change with a recovery plan.

This implementation changes repository code, migrations, configuration examples,
and documentation only. It does not make live WorkOS, GitHub, Fly, or database
production changes.
