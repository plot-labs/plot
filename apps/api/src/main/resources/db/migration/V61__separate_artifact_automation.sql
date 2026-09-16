alter table work_sessions
  add column session_kind varchar not null default 'CHAT';

update work_sessions
set session_kind = 'ROUTINE'
where routine_execution_id is not null;

alter table agent_runs
  drop constraint agent_runs_origin_check,
  drop constraint agent_runs_origin_value_check;

update agent_runs agent
set origin = 'AUTOMATION'
where agent.origin = 'CHAT'
  and exists (
    select 1
    from github_release_draft_requests release
    where release.workspace_id = agent.workspace_id
      and release.agent_run_id = agent.id
      and release.routine_id is null
  );

update work_sessions session
set session_kind = 'AUTOMATION'
where exists (
  select 1
  from agent_runs agent
  where agent.workspace_id = session.workspace_id
    and agent.work_session_id = session.id
    and agent.origin = 'AUTOMATION'
);

alter table work_sessions
  add constraint work_sessions_kind_check
  check (session_kind in ('CHAT', 'ROUTINE', 'AUTOMATION'));

alter table agent_runs
  add constraint agent_runs_origin_check
  check (
    (origin = 'ROUTINE' and routine_execution_id is not null and routine_id is not null)
    or (origin in ('CHAT', 'AUTOMATION') and routine_execution_id is null and routine_id is null)
  ),
  add constraint agent_runs_origin_value_check
  check (origin in ('ROUTINE', 'CHAT', 'AUTOMATION'));

create unique index agent_runs_automation_request_key_idx
  on agent_runs(workspace_id, idempotency_key)
  where origin = 'AUTOMATION';

alter table signal_evaluations
  add column admitted_agent_run_id uuid;

update signal_evaluations evaluation
set admitted_agent_run_id = version.agent_run_id
from chat_response_versions version
where version.workspace_id = evaluation.workspace_id
  and version.id = evaluation.admitted_response_version_id;

alter table signal_evaluations
  add constraint signal_evaluations_admitted_agent_run_fk
  foreign key (workspace_id, admitted_agent_run_id)
  references agent_runs(workspace_id, id) on delete set null;
