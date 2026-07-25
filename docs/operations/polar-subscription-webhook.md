# Polar subscription webhook operations

This runbook operates Plot's minimal Polar subscription synchronization. It
promotes a matched workspace to `founding` on `subscription.active` or
`subscription.uncanceled`, keeps access unchanged on `subscription.canceled`,
and makes the existing `founding` workspace read-only on
`subscription.revoked`.

This is not a self-service billing system. It does not create checkouts, enforce
a Founding pack limit, provide a customer portal, or process renewals. Trial
access is enforced per workspace until the earlier of 30 days or three
successful content packs; failed generations do not consume the pack allowance.
After Trial expiry or subscription revocation, reads and content export remain
available while new generation, import/sync, edits, and other mutations return
`WORKSPACE_READ_ONLY`. Better Auth sessions remain valid intentionally so
members can read, export, and reactivate.

## Prepare a partner checkout

1. Add the partner's sign-in email to `AUTH_ALLOWED_EMAILS`.
2. Have the partner sign in once so Plot creates the user and workspace.
3. Resolve the active workspace that will own the subscription:

   ```sql
   select u.id as user_id, wm.workspace_id
   from users u
   join workspace_members wm
     on wm.user_id = u.id
    and wm.status = 'ACTIVE'
   join workspaces w
     on w.id = wm.workspace_id
    and w.status = 'ACTIVE'
   where lower(u.email) = lower('<partner sign-in email>')
   order by wm.created_at
   limit 1;
   ```

4. Send the partner a URL based on the persistent Checkout Link:

   ```text
   https://buy.polar.sh/<checkout-link-slug>?reference_id=<workspace_id>&customer_email=<URL-encoded-sign-in-email>
   ```

Use the workspace ID as `reference_id` because the subscription is sold per
workspace. The receiver also accepts the original user-ID format for existing
links and then selects that user's earliest active workspace. Email is only the
last-resort fallback, so instruct the partner to pay with the same email used to
sign in.

## Configure and test the endpoint

Polar production and sandbox are isolated. Create the product and webhook
endpoint separately in each environment.

For local sandbox testing, enable the receiver and use the secret printed by the
Polar CLI:

```bash
export PLOT_POLAR_ENABLED=true
export PLOT_POLAR_WEBHOOKSECRET='<sandbox webhook secret>'
polar listen http://127.0.0.1:8080/api/polar/webhook
```

In Polar, configure a **Raw** webhook endpoint with these events:

- `subscription.active`
- `subscription.canceled`
- `subscription.uncanceled`
- `subscription.revoked`

For production, register:

```text
https://useplot-api.fly.dev/api/polar/webhook
```

Stage the generated production secret and deploy the migration and receiver
together:

```bash
flyctl secrets set --stage \
  -a useplot-api \
  PLOT_POLAR_WEBHOOKSECRET='<production webhook secret>'

flyctl deploy ./apps/api
```

`PLOT_POLAR_ENABLED=true` is public configuration committed in `fly.toml`; the
webhook secret must exist only in Fly secrets. A successful delivery returns
HTTP 204. An invalid signature returns 401, and a missing/disabled configuration
returns 503.

## Monitor and recover

Inspect recent outcomes without exposing customer email:

```sql
select webhook_id, event_type, subscription_id, outcome,
       matched_user_id, matched_workspace_id, received_at
from polar_webhook_events
order by received_at desc
limit 50;
```

Expected outcomes are:

- `PROMOTED`: workspace changed to `founding`, `active`, and full access
- `DEMOTED`: the same `founding` workspace changed to `revoked` and read-only;
  its sessions remain valid
- `IGNORED`: event intentionally did not change access, including `canceled`
- `UNMATCHED`: no user/workspace could be resolved; investigate manually
- `STALE_SUBSCRIPTION`: an old subscription attempted to revoke a workspace
  now associated with another subscription

`UNMATCHED` returns 204 intentionally so repeated bad deliveries do not disable
the endpoint. Since redelivery keeps the same `webhook-id`, correcting the
matching data does not automatically replay that event; update the affected
workspace from the verified Polar dashboard record and retain the audit row.
Never log or paste the checkout email, webhook secret, full payload, or session
tokens into an issue or runbook.

The public workspace projection exposes `plan`, `entitlementStatus`,
`accessMode`, and `trialEndsAt`. Polar customer and subscription identifiers
remain internal.
