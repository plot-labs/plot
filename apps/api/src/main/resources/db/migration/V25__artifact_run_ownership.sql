create table artifact_runs (
  id uuid primary key,
  workspace_id uuid not null,
  agent_run_id uuid not null,
  created_by_user_id uuid not null references users(id),
  idempotency_key text not null,
  request_fingerprint text not null,
  status varchar not null,
  error_code text,
  transition_version bigint not null default 0,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, agent_run_id),
  foreign key (workspace_id, agent_run_id)
    references agent_runs(workspace_id, id) on delete restrict,
  check (length(trim(idempotency_key)) > 0),
  check (length(trim(request_fingerprint)) > 0),
  check (status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING', 'READY', 'NEEDS_REVIEW', 'FAILED')),
  check (transition_version >= 0),
  check (finished_at is null or finished_at >= coalesce(started_at, created_at))
);

create unique index artifact_runs_agent_idempotency_key_idx
  on artifact_runs(workspace_id, agent_run_id, idempotency_key);

create index artifact_runs_workspace_created_idx
  on artifact_runs(workspace_id, created_at desc, id);

alter table generation_runs
  add column artifact_run_id uuid;

alter table generation_runs
  add constraint generation_runs_artifact_run_fk
  foreign key (workspace_id, artifact_run_id)
  references artifact_runs(workspace_id, id) on delete restrict;

create index generation_runs_artifact_run_idx
  on generation_runs(workspace_id, artifact_run_id, created_at, id)
  where artifact_run_id is not null;
