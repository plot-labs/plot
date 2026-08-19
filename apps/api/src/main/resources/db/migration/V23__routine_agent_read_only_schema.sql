alter table routines
  add constraint routines_execution_source_key
  unique (workspace_id, id, source_scope_id);

create table routine_context_sources (
  id uuid primary key,
  workspace_id uuid not null,
  routine_id uuid not null,
  source_scope_id uuid not null,
  order_index integer not null,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, routine_id, source_scope_id),
  unique (workspace_id, routine_id, order_index),
  foreign key (workspace_id, routine_id)
    references routines(workspace_id, id) on delete cascade,
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  check (order_index >= 0)
);

create index routine_context_sources_routine_idx
  on routine_context_sources(workspace_id, routine_id, order_index);

create table routine_executions (
  id uuid primary key,
  workspace_id uuid not null,
  routine_id uuid not null,
  created_by_user_id uuid not null references users(id),
  trigger_source_scope_id uuid not null,
  trigger_kind varchar not null,
  trigger_key text not null,
  request_fingerprint text not null,
  trigger_delivery_id uuid references github_webhook_deliveries(id) on delete restrict,
  scheduled_for timestamptz,
  refresh_from timestamptz,
  refresh_to timestamptz,
  refresh_continuation jsonb,
  refresh_completed_at timestamptz,
  activity_cursor_before bigint,
  activity_cursor_after bigint,
  status varchar not null,
  attempt_count integer not null default 0,
  transition_version bigint not null default 0,
  claimed_by text,
  claimed_at timestamptz,
  next_attempt_at timestamptz,
  error_code varchar,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, routine_id, trigger_key),
  unique (workspace_id, id, routine_id),
  foreign key (workspace_id, routine_id)
    references routines(workspace_id, id) on delete restrict,
  foreign key (workspace_id, routine_id, trigger_source_scope_id)
    references routines(workspace_id, id, source_scope_id) on delete restrict,
  foreign key (workspace_id, trigger_source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  check (length(trim(trigger_key)) > 0),
  check (length(trim(request_fingerprint)) > 0),
  check (trigger_kind in ('SCHEDULED', 'GITHUB', 'MANUAL')),
  check (
    (trigger_kind = 'GITHUB' and trigger_delivery_id is not null)
    or (trigger_kind in ('SCHEDULED', 'MANUAL') and trigger_delivery_id is null)
  ),
  check (status in ('PROBING', 'NO_ACTIVITY', 'DISPATCHED', 'FAILED')),
  check (attempt_count >= 0),
  check (transition_version >= 0),
  check (
    (claimed_by is null and claimed_at is null)
    or (claimed_by is not null and claimed_at is not null)
  ),
  check (finished_at is null or finished_at >= coalesce(started_at, created_at)),
  check (activity_cursor_after is null or activity_cursor_before is null
    or activity_cursor_after >= activity_cursor_before)
);

create index routine_executions_runnable_idx
  on routine_executions(status, next_attempt_at, created_at, id)
  where status = 'PROBING';

create index routine_executions_routine_created_idx
  on routine_executions(workspace_id, routine_id, created_at desc, id desc);

create table routine_execution_evidence (
  execution_id uuid not null,
  workspace_id uuid not null,
  writing_block_id uuid not null,
  activity_sequence bigint not null,
  order_index integer not null,
  primary key (execution_id, order_index),
  unique (execution_id, writing_block_id),
  foreign key (workspace_id, execution_id)
    references routine_executions(workspace_id, id) on delete cascade,
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  check (activity_sequence >= 0),
  check (order_index >= 0)
);

create index routine_execution_evidence_order_idx
  on routine_execution_evidence(workspace_id, execution_id, order_index);

create table agent_runs (
  id uuid primary key,
  workspace_id uuid not null,
  routine_execution_id uuid not null,
  routine_id uuid not null,
  work_session_id uuid not null,
  created_by_user_id uuid not null references users(id),
  instruction_snapshot text not null,
  prompt_version text not null,
  tool_policy_version text not null,
  budget_snapshot jsonb not null,
  status varchar not null,
  current_step integer not null default 0,
  attempt_count integer not null default 0,
  max_attempts integer not null default 3,
  next_attempt_at timestamptz,
  failure_code varchar,
  claimed_by text,
  claimed_at timestamptz,
  transition_version bigint not null default 0,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  model_call_count integer not null default 0,
  tool_call_count integer not null default 0,
  unique (workspace_id, id),
  unique (workspace_id, routine_execution_id),
  unique (workspace_id, id, routine_id),
  unique (workspace_id, work_session_id),
  foreign key (workspace_id, routine_execution_id)
    references routine_executions(workspace_id, id) on delete restrict,
  foreign key (workspace_id, routine_execution_id, routine_id)
    references routine_executions(workspace_id, id, routine_id) on delete restrict,
  foreign key (workspace_id, work_session_id)
    references work_sessions(workspace_id, id) on delete restrict,
  check (length(trim(instruction_snapshot)) > 0),
  check (length(trim(prompt_version)) > 0),
  check (length(trim(tool_policy_version)) > 0),
  check (jsonb_typeof(budget_snapshot) = 'object'),
  check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  check (current_step >= 0),
  check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts),
  check (transition_version >= 0),
  check (model_call_count >= 0 and tool_call_count >= 0),
  check (
    (claimed_by is null and claimed_at is null)
    or (claimed_by is not null and claimed_at is not null)
  ),
  check (finished_at is null or finished_at >= coalesce(started_at, created_at))
);

create index agent_runs_runnable_idx
  on agent_runs(status, next_attempt_at, created_at, id)
  where status in ('QUEUED', 'RUNNING');

create index agent_runs_workspace_created_idx
  on agent_runs(workspace_id, created_at desc, id desc);

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

alter table agent_runs
  add constraint agent_runs_work_session_routine_execution_fk
  foreign key (workspace_id, work_session_id, routine_execution_id)
  references work_sessions(workspace_id, id, routine_execution_id)
  on delete restrict;

alter table generation_runs
  add column agent_run_id uuid;

alter table generation_runs
  add constraint generation_runs_agent_run_fk
  foreign key (workspace_id, agent_run_id)
  references agent_runs(workspace_id, id)
  on delete restrict;

alter table generation_runs
  add constraint generation_runs_agent_run_key
  unique (workspace_id, id, agent_run_id);

create unique index generation_runs_agent_run_active_attempt_idx
  on generation_runs(workspace_id, agent_run_id)
  where agent_run_id is not null
    and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING');

create unique index generation_runs_agent_run_materialized_attempt_idx
  on generation_runs(workspace_id, agent_run_id)
  where agent_run_id is not null
    and status in ('READY', 'NEEDS_REVIEW');

create unique index work_sessions_one_per_routine_execution_idx
  on work_sessions(workspace_id, routine_execution_id)
  where routine_execution_id is not null;

create table agent_run_sources (
  id uuid primary key,
  workspace_id uuid not null,
  agent_run_id uuid not null,
  source_scope_id uuid not null,
  source_role varchar not null,
  order_index integer not null,
  captured_status varchar not null,
  captured_status_changed_at timestamptz not null,
  captured_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, agent_run_id, source_scope_id),
  unique (workspace_id, agent_run_id, order_index),
  foreign key (workspace_id, agent_run_id)
    references agent_runs(workspace_id, id) on delete cascade,
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  check (source_role in ('TRIGGER', 'CONTEXT')),
  check (order_index >= 0),
  check (captured_status in ('ACTIVE', 'DISABLED', 'ERROR'))
);

create unique index agent_run_sources_one_trigger_idx
  on agent_run_sources(workspace_id, agent_run_id)
  where source_role = 'TRIGGER';

create index agent_run_sources_scope_idx
  on agent_run_sources(workspace_id, source_scope_id, agent_run_id);

create table agent_run_inputs (
  id uuid primary key,
  workspace_id uuid not null,
  agent_run_id uuid not null,
  routine_id uuid,
  source_scope_id uuid not null,
  writing_block_id uuid not null,
  input_kind varchar not null,
  order_index integer not null,
  activity_sequence bigint,
  snapshot_title text,
  snapshot_body text not null,
  snapshot_excerpt text,
  original_url text not null,
  source_created_at timestamptz,
  source_updated_at timestamptz,
  content_hash text not null,
  source_provider varchar not null,
  source_kind text not null,
  source_label text not null,
  captured_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, agent_run_id),
  unique (workspace_id, agent_run_id, order_index),
  foreign key (workspace_id, agent_run_id, source_scope_id)
    references agent_run_sources(workspace_id, agent_run_id, source_scope_id)
    on delete cascade,
  foreign key (workspace_id, agent_run_id, routine_id)
    references agent_runs(workspace_id, id, routine_id)
    on delete cascade,
  foreign key (workspace_id, routine_id)
    references routines(workspace_id, id) on delete restrict,
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  check (input_kind in ('SEED', 'TOOL_RESULT')),
  check (order_index >= 0),
  check (length(trim(snapshot_body)) > 0),
  check (length(trim(original_url)) > 0),
  check (length(trim(content_hash)) > 0),
  check (source_provider = 'GITHUB'),
  check (length(trim(source_kind)) > 0),
  check (length(trim(source_label)) > 0),
  check (
    (input_kind = 'SEED' and routine_id is not null and activity_sequence is not null)
    or (input_kind = 'TOOL_RESULT' and routine_id is null and activity_sequence is null)
  )
);

create unique index agent_run_inputs_seed_identity_idx
  on agent_run_inputs(workspace_id, routine_id, writing_block_id, activity_sequence)
  where input_kind = 'SEED';

create unique index agent_run_inputs_adopted_identity_idx
  on agent_run_inputs(workspace_id, agent_run_id, source_scope_id, writing_block_id, content_hash)
  where input_kind = 'TOOL_RESULT';

create unique index agent_run_inputs_source_provenance_key
  on agent_run_inputs(workspace_id, id, agent_run_id, source_scope_id);

create index agent_run_inputs_agent_order_idx
  on agent_run_inputs(workspace_id, agent_run_id, order_index);

create table agent_steps (
  id uuid primary key,
  workspace_id uuid not null,
  agent_run_id uuid not null,
  sequence integer not null,
  step_kind varchar not null,
  status varchar not null,
  idempotency_key text not null,
  tool_name text,
  arguments jsonb not null default '{}'::jsonb,
  result jsonb,
  adopted_input_id uuid,
  generation_run_id uuid,
  failure_code varchar,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, agent_run_id, sequence),
  unique (workspace_id, agent_run_id, idempotency_key),
  foreign key (workspace_id, agent_run_id)
    references agent_runs(workspace_id, id) on delete cascade,
  foreign key (workspace_id, adopted_input_id, agent_run_id)
    references agent_run_inputs(workspace_id, id, agent_run_id) on delete restrict,
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, generation_run_id, agent_run_id)
    references generation_runs(workspace_id, id, agent_run_id) on delete restrict,
  check (sequence >= 0),
  check (step_kind in ('READ_TOOL', 'ARTIFACT_HANDOFF')),
  check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  check (length(trim(idempotency_key)) > 0),
  check (jsonb_typeof(arguments) = 'object'),
  check (result is null or jsonb_typeof(result) = 'object'),
  check ((step_kind = 'READ_TOOL' and tool_name is not null and generation_run_id is null)
    or (step_kind = 'ARTIFACT_HANDOFF' and tool_name is null and adopted_input_id is null)),
  check (finished_at is null or finished_at >= coalesce(started_at, created_at))
);

create unique index agent_steps_one_handoff_per_generation_idx
  on agent_steps(workspace_id, generation_run_id)
  where step_kind = 'ARTIFACT_HANDOFF' and generation_run_id is not null;

create index agent_steps_agent_order_idx
  on agent_steps(workspace_id, agent_run_id, sequence);

alter table generation_inputs
  add column source_scope_id uuid,
  add column agent_run_id uuid,
  add column agent_run_input_id uuid;

-- generation_inputs is immutable for application writes. This migration alone
-- enriches legacy rows with their already-recorded generation-run provenance.
-- PostgreSQL runs this SQL migration transactionally, so an interrupted
-- backfill also restores the trigger before another process can write rows.
alter table generation_inputs disable trigger generation_inputs_append_only;

update generation_inputs input
set source_scope_id = run.source_scope_id
from generation_runs run
where run.workspace_id = input.workspace_id
  and run.id = input.generation_run_id;

alter table generation_inputs enable trigger generation_inputs_append_only;

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
