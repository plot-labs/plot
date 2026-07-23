alter table work_sessions
  add column latest_generation_run_id uuid;

alter table work_sessions
  add constraint work_sessions_latest_generation_run_fk
  foreign key (workspace_id, latest_generation_run_id)
  references generation_runs(workspace_id, id)
  on delete restrict;

create index work_sessions_workspace_latest_generation_idx
  on work_sessions(workspace_id, latest_generation_run_id)
  where latest_generation_run_id is not null;
