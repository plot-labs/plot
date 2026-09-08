-- R08: ContentSourceSnapshot for reusing evidence across content types

create table content_source_snapshots (
  id uuid primary key,
  workspace_id uuid not null,
  source_bundle_hash varchar(64) not null,
  source_scope_id uuid,
  base_ref varchar,
  head_ref varchar,
  captured_at timestamptz not null,
  brief_snapshot jsonb,
  inputs_snapshot jsonb not null,
  created_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id) references workspaces(id) on delete restrict,
  foreign key (workspace_id, source_scope_id) references source_scopes(workspace_id, id) on delete restrict
);

create index content_source_snapshots_bundle_hash_idx
  on content_source_snapshots(workspace_id, source_bundle_hash);

alter table agent_runs
  add column source_snapshot_id uuid;

alter table agent_runs
  add constraint agent_runs_source_snapshot_fk
  foreign key (workspace_id, source_snapshot_id)
    references content_source_snapshots(workspace_id, id) on delete restrict;

create index agent_runs_source_snapshot_idx
  on agent_runs(workspace_id, source_snapshot_id);
