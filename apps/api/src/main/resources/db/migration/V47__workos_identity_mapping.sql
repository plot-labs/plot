-- WorkOS is the account-auth authority. These tables keep the explicit
-- provider-to-Plot projection and the durable bootstrap recovery record.
alter table workspace_members
  add column workos_membership_id text;

create unique index workspace_members_workos_membership_uidx
  on workspace_members(workos_membership_id)
  where workos_membership_id is not null;

create table workos_identity_mappings (
  workos_user_id text primary key,
  plot_user_id uuid not null unique references users(id) on delete cascade,
  email text not null,
  email_verified boolean not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create index workos_identity_mappings_plot_user_idx
  on workos_identity_mappings(plot_user_id);

create table workos_organization_mappings (
  workos_organization_id text primary key,
  workos_user_id text not null references workos_identity_mappings(workos_user_id) on delete cascade,
  workspace_id uuid not null unique references workspaces(id) on delete cascade,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create index workos_organization_mappings_workos_user_idx
  on workos_organization_mappings(workos_user_id);

create table workos_provisioning (
  workos_user_id text primary key,
  plot_user_id uuid references users(id) on delete set null,
  workspace_id uuid references workspaces(id) on delete set null,
  workos_organization_id text,
  idempotency_key text not null unique,
  state varchar not null,
  last_error text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint workos_provisioning_state_check
    check (state in ('PENDING', 'ORGANIZATION_READY', 'COMPLETED', 'FAILED'))
);

create unique index workos_provisioning_organization_uidx
  on workos_provisioning(workos_organization_id)
  where workos_organization_id is not null;
