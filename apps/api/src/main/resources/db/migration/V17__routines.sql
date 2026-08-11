create table routines (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  created_by_user_id uuid not null references users(id),
  source_scope_id uuid not null,
  name text not null,
  instruction text not null,
  cadence varchar not null,
  enabled boolean not null default true,
  last_run_at timestamptz,
  next_run_at timestamptz not null,
  last_generation_run_id uuid,
  last_run_status varchar,
  last_error_code varchar,
  claimed_by text,
  claimed_at timestamptz,
  transition_version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  foreign key (workspace_id, last_generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  check (length(trim(name)) > 0),
  check (length(trim(instruction)) > 0),
  check (cadence in ('DAILY', 'WEEKLY')),
  check (last_run_status is null or last_run_status in
    ('NO_ACTIVITY', 'QUEUED', 'WRITING', 'REVIEWING', 'REWRITING', 'READY', 'NEEDS_REVIEW', 'FAILED')),
  check ((claimed_by is null and claimed_at is null)
    or (claimed_by is not null and claimed_at is not null)),
  check (transition_version >= 0)
);

create index routines_due_idx
  on routines(next_run_at, created_at)
  where enabled = true;

create index routines_workspace_idx
  on routines(workspace_id, created_at desc);

create index routines_stale_claim_idx
  on routines(claimed_at)
  where claimed_by is not null;
