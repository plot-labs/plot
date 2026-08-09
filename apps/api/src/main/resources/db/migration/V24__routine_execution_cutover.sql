-- V23 introduced the canonical execution identity.  V24 retires the old
-- GitHub queue without rewriting its historical rows or generation links.
alter table routine_executions
  add column legacy_generation_run_id uuid;

alter table routine_executions
  add constraint routine_executions_legacy_generation_fk
  foreign key (workspace_id, legacy_generation_run_id)
  references generation_runs(workspace_id, id)
  on delete restrict;

create table routine_execution_evidence (
  execution_id uuid not null,
  workspace_id uuid not null,
  writing_block_id uuid not null,
  activity_sequence bigint not null,
  order_index integer not null,
  legacy_event_run_id uuid,
  primary key (execution_id, order_index),
  unique (execution_id, writing_block_id),
  foreign key (workspace_id, execution_id)
    references routine_executions(workspace_id, id) on delete cascade,
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  foreign key (workspace_id, legacy_event_run_id)
    references routine_github_event_runs(workspace_id, id) on delete restrict,
  check (activity_sequence >= 0),
  check (order_index >= 0)
);

create index routine_execution_evidence_order_idx
  on routine_execution_evidence(workspace_id, execution_id, order_index);

alter table routine_github_event_runs
  add column retired_at timestamptz,
  add column retirement_code varchar;

alter table routine_github_event_runs
  add constraint routine_github_event_runs_retirement_code_check
  check (retirement_code is null or retirement_code in ('CANONICAL_EXECUTION_CUTOVER'));

-- Keep the legacy event id as the canonical execution id.  Terminal direct
-- generations are represented as failed legacy executions only: this does
-- not manufacture a Chat, AgentRun, or AgentStep for historical work.
insert into routine_executions (
  id, workspace_id, routine_id, created_by_user_id, trigger_source_scope_id,
  trigger_kind, trigger_key, request_fingerprint, trigger_delivery_id,
  activity_cursor_before, status, attempt_count, transition_version,
  error_code, finished_at, created_at, updated_at, legacy_generation_run_id
)
select
  event_run.id,
  event_run.workspace_id,
  event_run.routine_id,
  routine.created_by_user_id,
  routine.source_scope_id,
  'GITHUB',
  'github:' || event_run.routine_id::text || ':' || event_run.delivery_id::text,
  event_run.routine_id::text || '|' || delivery.payload_hash || '|' ||
    delivery.event_type || '|' || coalesce(delivery.event_action, '') ||
    coalesce((
      select string_agg('|' || block.external_object_key, '' order by evidence.order_index)
      from routine_github_event_evidence evidence
      join writing_blocks block
        on block.workspace_id = evidence.workspace_id
       and block.id = evidence.writing_block_id
      where evidence.event_run_id = event_run.id
    ), ''),
  event_run.delivery_id,
  routine.activity_cursor_sequence,
  'FAILED',
  event_run.attempt_count,
  event_run.transition_version,
  case
    when event_run.status = 'SUCCEEDED' then 'LEGACY_DIRECT_GENERATION'
    when event_run.status in ('QUEUED', 'PROCESSING') then 'LEGACY_EVENT_CUTOVER'
    else event_run.error_code
  end,
  case when event_run.status in ('SUCCEEDED', 'FAILED', 'QUEUED', 'PROCESSING')
    then coalesce(event_run.finished_at, event_run.updated_at)
    else null
  end,
  event_run.created_at,
  event_run.updated_at,
  event_run.generation_run_id
from routine_github_event_runs event_run
join routines routine
  on routine.workspace_id = event_run.workspace_id
 and routine.id = event_run.routine_id
join github_webhook_deliveries delivery
  on delivery.id = event_run.delivery_id
on conflict (workspace_id, id) do nothing;

insert into routine_execution_evidence (
  execution_id, workspace_id, writing_block_id, activity_sequence,
  order_index, legacy_event_run_id
)
select
  evidence.event_run_id,
  evidence.workspace_id,
  evidence.writing_block_id,
  evidence.writing_block_activity_sequence,
  evidence.order_index,
  evidence.event_run_id
from routine_github_event_evidence evidence
on conflict (execution_id, order_index) do nothing;

-- Old rows remain queryable, but no longer participate in claims.  Clear only
-- the old routine claim marker; last_execution_id and generation_run_id stay
-- untouched for historical projections.
update routine_github_event_runs
set retired_at = coalesce(retired_at, now()),
    retirement_code = coalesce(retirement_code, 'CANONICAL_EXECUTION_CUTOVER');

update routines routine
set claimed_by = null,
    claimed_at = null,
    active_execution_id = null,
    transition_version = transition_version + 1,
    updated_at = now()
where routine.claimed_by like 'routine-github:%'
  and exists (
    select 1
    from routine_github_event_runs event_run
    where event_run.workspace_id = routine.workspace_id
      and event_run.routine_id = routine.id
      and event_run.retired_at is not null
      and routine.claimed_by = 'routine-github:' || event_run.id::text
  );

create index routine_github_event_runs_retired_idx
  on routine_github_event_runs(retired_at, created_at, id);
