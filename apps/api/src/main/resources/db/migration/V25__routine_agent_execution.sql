alter table agent_runs
  drop constraint agent_runs_work_session_routine_execution_fk,
  drop constraint agent_runs_workspace_id_work_session_id_fkey,
  drop constraint agent_runs_workspace_id_work_session_id_key,
  drop column work_session_id;

drop index work_sessions_one_per_routine_execution_idx;

alter table work_sessions
  drop constraint work_sessions_routine_execution_key,
  drop constraint work_sessions_routine_execution_fk,
  drop column routine_execution_id;

alter table agent_runs
  add column model_call_count integer not null default 0,
  add column tool_call_count integer not null default 0;

alter table agent_runs
  add constraint agent_runs_call_counts_check
  check (model_call_count >= 0 and tool_call_count >= 0);

alter table agent_run_inputs
  add column source_provider varchar,
  add column source_kind text,
  add column source_label text;

update agent_run_inputs input
set source_provider = coalesce(nullif(upper(block.platform), ''), 'GITHUB'),
    source_kind = block.source_kind,
    source_label = coalesce(nullif(trim(input.snapshot_title), ''), 'GitHub ' || block.source_kind)
from writing_blocks block
where block.workspace_id = input.workspace_id
  and block.id = input.writing_block_id;

alter table agent_run_inputs
  alter column source_provider set not null,
  alter column source_kind set not null,
  alter column source_label set not null;

alter table agent_run_inputs
  add constraint agent_run_inputs_source_provider_check
  check (source_provider = 'GITHUB'),
  add constraint agent_run_inputs_source_kind_check
  check (length(trim(source_kind)) > 0),
  add constraint agent_run_inputs_source_label_check
  check (length(trim(source_label)) > 0);

create unique index agent_run_inputs_adopted_identity_idx
  on agent_run_inputs(workspace_id, agent_run_id, source_scope_id, writing_block_id, content_hash)
  where input_kind = 'TOOL_RESULT';

alter table agent_run_inputs
  add constraint agent_run_inputs_source_provenance_key
  unique (workspace_id, id, agent_run_id, source_scope_id);

alter table generation_inputs
  add column source_scope_id uuid,
  add column agent_run_id uuid,
  add column agent_run_input_id uuid;

update generation_inputs input
set source_scope_id = run.source_scope_id
from generation_runs run
where run.workspace_id = input.workspace_id
  and run.id = input.generation_run_id;

alter table generation_inputs
  add constraint generation_inputs_source_scope_fk
  foreign key (workspace_id, source_scope_id)
  references source_scopes(workspace_id, id) on delete restrict,
  add constraint generation_inputs_agent_run_fk
  foreign key (workspace_id, generation_run_id, agent_run_id)
  references generation_runs(workspace_id, id, agent_run_id) on delete restrict,
  add constraint generation_inputs_agent_input_fk
  foreign key (workspace_id, agent_run_input_id, agent_run_id, source_scope_id)
  references agent_run_inputs(workspace_id, id, agent_run_id, source_scope_id) on delete restrict,
  add constraint generation_inputs_agent_provenance_check
  check (
    (agent_run_id is null and agent_run_input_id is null)
    or (agent_run_id is not null and agent_run_input_id is not null and source_scope_id is not null)
  );

create unique index generation_inputs_agent_input_idx
  on generation_inputs(workspace_id, generation_run_id, agent_run_input_id)
  where agent_run_input_id is not null;
