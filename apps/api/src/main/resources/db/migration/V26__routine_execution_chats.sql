alter table work_sessions
  add column routine_execution_id uuid;

alter table work_sessions
  add constraint work_sessions_routine_execution_fk
  foreign key (workspace_id, routine_execution_id)
  references routine_executions(workspace_id, id)
  on delete restrict;

alter table work_sessions
  add constraint work_sessions_routine_execution_key
  unique (workspace_id, id, routine_execution_id);

create unique index work_sessions_one_per_routine_execution_idx
  on work_sessions(workspace_id, routine_execution_id)
  where routine_execution_id is not null;

alter table agent_runs
  add column work_session_id uuid;

alter table agent_runs
  add constraint agent_runs_workspace_id_work_session_id_fkey
  foreign key (workspace_id, work_session_id)
  references work_sessions(workspace_id, id)
  on delete restrict;

alter table agent_runs
  add constraint agent_runs_work_session_routine_execution_fk
  foreign key (workspace_id, work_session_id, routine_execution_id)
  references work_sessions(workspace_id, id, routine_execution_id)
  on delete restrict;

alter table agent_runs
  add constraint agent_runs_workspace_id_work_session_id_key
  unique (workspace_id, work_session_id);
