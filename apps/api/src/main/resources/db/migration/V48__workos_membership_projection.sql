-- WorkOS membership events are accepted once, then processed from a durable
-- inbox. The payload itself is intentionally not retained; the hash and
-- provider identifiers are enough for replay detection and reconciliation.
create table workos_membership_event_inbox (
  event_id text primary key,
  event_type text not null,
  payload_hash text not null unique,
  workos_membership_id text,
  workos_organization_id text,
  workos_user_id text,
  state varchar not null,
  attempt_count integer not null default 0,
  received_at timestamptz not null,
  processed_at timestamptz,
  next_attempt_at timestamptz,
  last_error text,
  constraint workos_membership_event_state_check
    check (state in ('RECEIVED', 'PROCESSED', 'IGNORED', 'RETRY', 'DEAD_LETTER'))
);

create index workos_membership_event_retry_idx
  on workos_membership_event_inbox(state, next_attempt_at)
  where state = 'RETRY';

-- A workspace creation can finish after WorkOS has already created its
-- Organization. Keep the provider identity and local projection state so a
-- retry converges instead of creating a second Organization.
create table workos_workspace_provisioning (
  workos_organization_id text primary key,
  workos_user_id text not null references workos_identity_mappings(workos_user_id) on delete cascade,
  workspace_id uuid references workspaces(id) on delete set null,
  idempotency_key text not null unique,
  state varchar not null,
  last_error text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint workos_workspace_provisioning_state_check
    check (state in ('PENDING', 'ORGANIZATION_READY', 'COMPLETED', 'FAILED'))
);

create unique index workos_workspace_provisioning_workspace_uidx
  on workos_workspace_provisioning(workspace_id)
  where workspace_id is not null;
