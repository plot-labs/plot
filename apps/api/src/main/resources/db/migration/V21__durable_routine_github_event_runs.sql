create table routine_github_event_runs (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  routine_id uuid not null,
  delivery_id uuid not null references github_webhook_deliveries(id) on delete cascade,
  status varchar not null,
  attempt_count integer not null default 0,
  transition_version bigint not null default 0,
  generation_run_id uuid,
  error_code varchar,
  claimed_by text,
  claimed_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  finished_at timestamptz,
  unique (workspace_id, id),
  unique (routine_id, delivery_id),
  unique (generation_run_id),
  foreign key (workspace_id, routine_id)
    references routines(workspace_id, id) on delete cascade,
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  check (status in ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
  check (attempt_count >= 0),
  check (transition_version >= 0),
  check (
    (status = 'PROCESSING' and claimed_by is not null and claimed_at is not null)
    or (status <> 'PROCESSING' and claimed_by is null and claimed_at is null)
  ),
  check (
    (status = 'SUCCEEDED' and generation_run_id is not null and error_code is null)
    or (status = 'FAILED' and generation_run_id is null and error_code is not null)
    or (status in ('QUEUED', 'PROCESSING') and generation_run_id is null and error_code is null)
  ),
  check ((status in ('SUCCEEDED', 'FAILED') and finished_at is not null)
    or (status in ('QUEUED', 'PROCESSING') and finished_at is null))
);

create index routine_github_event_runs_runnable_idx
  on routine_github_event_runs(status, claimed_at, created_at, id)
  where status in ('QUEUED', 'PROCESSING');

create table routine_github_event_evidence (
  event_run_id uuid not null,
  workspace_id uuid not null,
  writing_block_id uuid not null,
  writing_block_activity_sequence bigint not null,
  order_index integer not null,
  primary key (event_run_id, order_index),
  unique (event_run_id, writing_block_id),
  foreign key (workspace_id, event_run_id)
    references routine_github_event_runs(workspace_id, id) on delete cascade,
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  check (order_index >= 0)
);
