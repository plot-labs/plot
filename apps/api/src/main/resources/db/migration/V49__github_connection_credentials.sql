-- GitHub repository access is a product capability, not an account-auth
-- credential. Keep the old auth_account table immutable for the separate
-- retention decision and stop reading it from the active connection path.
create table github_product_credentials (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  github_account_id bigint not null,
  github_login text,
  access_token_ciphertext text not null,
  refresh_token_ciphertext text,
  scope text not null,
  status varchar not null default 'ACTIVE',
  revoked_at timestamptz,
  encryption_key_version varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (user_id, github_account_id),
  check (status in ('ACTIVE', 'RETIRED')),
  check ((status = 'ACTIVE' and revoked_at is null) or status = 'RETIRED')
);

create unique index github_product_credentials_active_user_uidx
  on github_product_credentials(user_id)
  where status = 'ACTIVE';

create index github_product_credentials_status_idx
  on github_product_credentials(status, updated_at);

create table github_product_oauth_states (
  state_hash varchar primary key,
  user_id uuid not null references users(id) on delete cascade,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  return_path varchar not null,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null,
  check (return_path in ('/settings/integrations', '/chat')),
  check (expires_at > created_at)
);

create index github_product_oauth_states_expiry_idx
  on github_product_oauth_states(expires_at)
  where consumed_at is null;

-- The backfill is deliberately an application operation: it can be resumed,
-- observed, and quarantined without placing token material in migration logs.
create table github_product_credential_backfill (
  id smallint primary key check (id = 1),
  last_source_id text,
  state varchar not null default 'PENDING',
  processed_count bigint not null default 0,
  migrated_count bigint not null default 0,
  quarantined_count bigint not null default 0,
  last_error varchar,
  started_at timestamptz,
  completed_at timestamptz,
  updated_at timestamptz not null,
  check (state in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

insert into github_product_credential_backfill (id, state, updated_at)
values (1, 'PENDING', current_timestamp);

create table github_product_credential_quarantine (
  source_account_id text primary key,
  source_user_id text,
  plot_user_id uuid references users(id) on delete set null,
  reason varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create index github_product_credential_quarantine_created_idx
  on github_product_credential_quarantine(created_at);
