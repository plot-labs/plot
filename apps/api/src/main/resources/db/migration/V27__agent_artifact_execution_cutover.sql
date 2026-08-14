-- Move historical Generation rows behind the AgentRun -> ArtifactRun ownership
-- boundary. Already-applied migrations remain immutable; this is the only
-- migration that reads their legacy identifiers.

-- Routine AgentRuns created before the Chat ownership cutover should still
-- have a durable Chat row before their work_session_id is repaired.
insert into work_sessions (
  id, workspace_id, title, status, created_by_user_id, last_activity_at,
  routine_execution_id, created_at, updated_at
)
select md5('legacy-agent-chat:' || agent.workspace_id::text || ':' || agent.id::text)::uuid,
       agent.workspace_id,
       coalesce(nullif(trim(agent.instruction_snapshot), ''), 'Imported routine execution'),
       'ACTIVE',
       agent.created_by_user_id,
       coalesce(agent.updated_at, agent.created_at),
       agent.routine_execution_id,
       agent.created_at,
       agent.updated_at
from agent_runs agent
where agent.work_session_id is null
on conflict (workspace_id, id) do nothing;

-- Untrusted legacy Generations receive a deterministic synthetic Chat. A
-- pre-existing work_session_id is retained because V15 established it from a
-- trusted session latest pointer.
insert into work_sessions (
  id, workspace_id, title, status, created_by_user_id, last_activity_at,
  routine_execution_id, created_at, updated_at
)
select md5('legacy-generation-chat:' || generation.workspace_id::text || ':' || generation.id::text)::uuid,
       generation.workspace_id,
       coalesce(nullif(trim(generation.user_instruction), ''), 'Imported artifact run'),
       'ACTIVE',
       generation.created_by_user_id,
       coalesce(generation.updated_at, generation.created_at),
       null,
       generation.created_at,
       generation.updated_at
from generation_runs generation
where generation.agent_run_id is null
  and generation.work_session_id is null
on conflict (workspace_id, id) do nothing;

update agent_runs agent
set work_session_id = md5('legacy-agent-chat:' || agent.workspace_id::text || ':' || agent.id::text)::uuid,
    updated_at = greatest(agent.updated_at, now())
where agent.work_session_id is null;

-- Materialize one synthetic Chat AgentRun per untrusted Generation. Existing
-- Chat-linked Generations do not receive a second Chat; they get a synthetic
-- AgentRun attached to their retained session instead.
insert into agent_runs (
  id, workspace_id, routine_execution_id, routine_id, work_session_id,
  created_by_user_id, origin, idempotency_key, request_fingerprint,
  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
  status, current_step, attempt_count, max_attempts, next_attempt_at,
  failure_code, claimed_by, claimed_at, transition_version, started_at,
  finished_at, created_at, updated_at, model_call_count, tool_call_count
)
select md5('legacy-generation-agent:' || generation.workspace_id::text || ':' || generation.id::text)::uuid,
       generation.workspace_id,
       null,
       null,
       coalesce(
         generation.work_session_id,
         md5('legacy-generation-chat:' || generation.workspace_id::text || ':' || generation.id::text)::uuid
       ),
       generation.created_by_user_id,
       'CHAT',
       'legacy:generation:' || generation.id::text,
       'legacy:generation:' || generation.id::text,
       coalesce(nullif(trim(generation.user_instruction), ''), 'Imported artifact run'),
       'chat-agent-v1',
       'read-only-v1',
       generation.budget_snapshot,
       case generation.status
         when 'READY' then 'SUCCEEDED'
         when 'NEEDS_REVIEW' then 'SUCCEEDED'
         when 'FAILED' then 'FAILED'
         when 'QUEUED' then 'QUEUED'
         else 'RUNNING'
       end,
       0,
       0,
       3,
       generation.next_attempt_at,
       generation.error_code,
       null,
       null,
       generation.transition_version,
       generation.started_at,
       generation.finished_at,
       generation.created_at,
       generation.updated_at,
       0,
       0
from generation_runs generation
where generation.agent_run_id is null
on conflict (workspace_id, id) do nothing;

update generation_runs generation
set agent_run_id = md5('legacy-generation-agent:' || generation.workspace_id::text || ':' || generation.id::text)::uuid,
    work_session_id = coalesce(
      generation.work_session_id,
      md5('legacy-generation-chat:' || generation.workspace_id::text || ':' || generation.id::text)::uuid
    ),
    updated_at = greatest(generation.updated_at, now())
where generation.agent_run_id is null;

-- Each AgentRun owns one internal ArtifactRun. Attempts linked to the same
-- AgentRun are represented by one child and keep their original workflow rows.
with latest as (
  select distinct on (generation.workspace_id, generation.agent_run_id)
         generation.workspace_id,
         generation.agent_run_id,
         generation.status,
         generation.error_code,
         generation.transition_version,
         generation.started_at,
         generation.finished_at,
         generation.created_at,
         generation.updated_at
  from generation_runs generation
  where generation.agent_run_id is not null
  order by generation.workspace_id, generation.agent_run_id, generation.created_at desc, generation.id desc
), aggregate_rows as (
  select generation.workspace_id,
         generation.agent_run_id,
         min(generation.created_at) as created_at,
         max(generation.updated_at) as updated_at
  from generation_runs generation
  where generation.agent_run_id is not null
  group by generation.workspace_id, generation.agent_run_id
)
insert into artifact_runs (
  id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
  request_fingerprint, status, error_code, transition_version, started_at,
  finished_at, created_at, updated_at
)
select md5('legacy-artifact-run:' || aggregate_rows.workspace_id::text || ':' || aggregate_rows.agent_run_id::text)::uuid,
       aggregate_rows.workspace_id,
       aggregate_rows.agent_run_id,
       agent.created_by_user_id,
       'legacy:artifact:' || aggregate_rows.agent_run_id::text,
       'legacy:artifact:' || aggregate_rows.agent_run_id::text,
       case latest.status
         when 'NEEDS_YOUR_CALL' then 'NEEDS_REVIEW'
         else latest.status
       end,
       latest.error_code,
       latest.transition_version,
       latest.started_at,
       latest.finished_at,
       aggregate_rows.created_at,
       aggregate_rows.updated_at
from aggregate_rows
join latest
  on latest.workspace_id = aggregate_rows.workspace_id
 and latest.agent_run_id = aggregate_rows.agent_run_id
join agent_runs agent
  on agent.workspace_id = aggregate_rows.workspace_id
 and agent.id = aggregate_rows.agent_run_id
where not exists (
  select 1
  from artifact_runs artifact
  where artifact.workspace_id = aggregate_rows.workspace_id
    and artifact.agent_run_id = aggregate_rows.agent_run_id
)
on conflict (workspace_id, agent_run_id) do nothing;

update generation_runs generation
set artifact_run_id = artifact.id,
    updated_at = greatest(generation.updated_at, now())
from artifact_runs artifact
where artifact.workspace_id = generation.workspace_id
  and artifact.agent_run_id = generation.agent_run_id
  and generation.artifact_run_id is null;
