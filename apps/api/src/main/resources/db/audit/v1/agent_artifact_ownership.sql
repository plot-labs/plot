with violations as (
  select
    'GENERATION_AGENT_MISSING'::text as violation_code,
    generation.workspace_id,
    generation.id as entity_id,
    'generation_runs.agent_run_id is null'::text as detail
  from generation_runs generation
  where generation.agent_run_id is null

  union all

  select
    'GENERATION_ARTIFACT_MISSING'::text,
    generation.workspace_id,
    generation.id,
    'generation_runs.artifact_run_id is null'::text
  from generation_runs generation
  where generation.artifact_run_id is null

  union all

  select
    'GENERATION_OWNER_CHAIN_MISMATCH'::text,
    generation.workspace_id,
    generation.id,
    format(
      'generation agent_run_id=%s but artifact agent_run_id=%s',
      generation.agent_run_id,
      artifact.agent_run_id
    )
  from generation_runs generation
  left join artifact_runs artifact
    on artifact.workspace_id = generation.workspace_id
   and artifact.id = generation.artifact_run_id
  where generation.agent_run_id is not null
    and generation.artifact_run_id is not null
    and (
      artifact.id is null
      or artifact.agent_run_id is distinct from generation.agent_run_id
    )

  union all

  select
    'AGENT_TERMINAL_ARTIFACT_MISMATCH'::text,
    agent.workspace_id,
    agent.id,
    case
      when artifact.id is null then 'succeeded AgentRun has no ArtifactRun'
      when artifact.status not in ('READY', 'NEEDS_REVIEW') then
        format('succeeded AgentRun owns ArtifactRun in status %s', artifact.status)
      else 'succeeded AgentRun owns an unmaterialized ArtifactRun'
    end
  from agent_runs agent
  left join artifact_runs artifact
    on artifact.workspace_id = agent.workspace_id
   and artifact.agent_run_id = agent.id
  where agent.status = 'SUCCEEDED'
    and (
      artifact.id is null
      or artifact.status not in ('READY', 'NEEDS_REVIEW')
      or not exists (
        select 1
        from generation_runs generation
        join content_packs pack
          on pack.workspace_id = generation.workspace_id
         and pack.generation_run_id = generation.id
        where generation.workspace_id = agent.workspace_id
          and generation.agent_run_id = agent.id
          and generation.artifact_run_id = artifact.id
      )
    )

  union all

  select
    'RELEASE_OWNERSHIP_MISMATCH'::text,
    release_request.workspace_id,
    release_request.id,
    format(
      'release agent_run_id=%s disagrees with generation agent_run_id=%s',
      release_request.agent_run_id,
      generation.agent_run_id
    )
  from github_release_draft_requests release_request
  join generation_runs generation
    on generation.workspace_id = release_request.workspace_id
   and generation.id = release_request.generation_run_id
  left join artifact_runs artifact
    on artifact.workspace_id = generation.workspace_id
   and artifact.id = generation.artifact_run_id
  where release_request.agent_run_id is not null
    and release_request.generation_run_id is not null
    and (
      generation.agent_run_id is distinct from release_request.agent_run_id
      or artifact.id is null
      or artifact.agent_run_id is distinct from release_request.agent_run_id
    )
)
select violation_code, workspace_id, entity_id, detail
from violations
order by violation_code, workspace_id, entity_id;
