alter table connections
  add column status_reason varchar,
  add column status_changed_at timestamptz default now();

update connections
set status_changed_at = updated_at
where status_changed_at is null;

alter table connections
  alter column status_changed_at set not null,
  add constraint connections_status_reason_check check (
    status_reason is null or status_reason in (
      'AUTH_EXPIRED',
      'INSTALLATION_SUSPENDED',
      'INSTALLATION_UNINSTALLED',
      'PROVIDER_VERIFICATION_FAILED'
    )
  );

alter table source_scopes
  add column status_reason varchar,
  add column status_changed_at timestamptz default now();

update source_scopes
set status_changed_at = updated_at
where status_changed_at is null;

alter table source_scopes
  alter column status_changed_at set not null,
  add constraint source_scopes_status_reason_check check (
    status_reason is null or status_reason in (
      'GRANT_REMOVED',
      'REPOSITORY_TRANSFERRED',
      'REPOSITORY_DELETED',
      'USER_DISCONNECTED',
      'PROVIDER_VERIFICATION_FAILED'
    )
  );

create table github_repository_access_checks (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  connection_id uuid not null,
  source_scope_id uuid not null,
  trigger varchar not null,
  status varchar not null,
  attempt_count integer not null default 0,
  transition_version bigint not null default 0,
  claimed_by text,
  claimed_at timestamptz,
  next_attempt_at timestamptz,
  error_code varchar,
  verified_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, source_scope_id),
  foreign key (workspace_id, connection_id)
    references connections(workspace_id, id) on delete restrict,
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  check (trigger in ('LIFECYCLE_EVENT', 'RETRY', 'CHECK_AGAIN')),
  check (status in ('QUEUED', 'CHECKING', 'VERIFIED', 'FAILED')),
  check (attempt_count >= 0),
  check (transition_version >= 0),
  check (
    (status = 'CHECKING' and claimed_by is not null and claimed_at is not null)
    or (status <> 'CHECKING' and claimed_by is null and claimed_at is null)
  ),
  check (status <> 'VERIFIED' or verified_at is not null)
);

create index github_repository_access_checks_runnable_idx
  on github_repository_access_checks(next_attempt_at, created_at)
  where status = 'QUEUED';

create index github_repository_access_checks_stale_claim_idx
  on github_repository_access_checks(claimed_at)
  where status = 'CHECKING';
