alter table generation_runs
  add column work_session_id uuid;

-- The old latest pointer is the only reliable relationship for historical rows.
-- Preserve it when it exists, while leaving all other generations unlinked.
update generation_runs run
set work_session_id = session.id
from work_sessions session
where session.workspace_id = run.workspace_id
  and session.latest_generation_run_id = run.id
  and run.work_session_id is null;

alter table generation_runs
  add constraint generation_runs_work_session_fk
  foreign key (workspace_id, work_session_id)
  references work_sessions(workspace_id, id)
  on delete restrict;

create index generation_runs_workspace_session_created_idx
  on generation_runs(workspace_id, work_session_id, created_at, id)
  where work_session_id is not null;
