create table chat_run_progress (
  workspace_id uuid not null,
  agent_run_id uuid not null,
  epoch bigint not null check (epoch > 0),
  revision bigint not null check (revision > 0),
  phase varchar not null check (phase in ('QUEUED','RESPONDING','RESEARCHING','WRITING','REVIEWING','REWRITING','COMPLETE','FAILED')),
  response_text text not null default '' check (char_length(response_text) <= 40000),
  draft_paragraphs jsonb not null default '[]'::jsonb
    check (jsonb_typeof(draft_paragraphs) = 'array' and char_length(draft_paragraphs::text) <= 800000),
  updated_at timestamptz not null default now(),
  primary key (workspace_id, agent_run_id),
  foreign key (workspace_id, agent_run_id) references agent_runs(workspace_id, id) on delete cascade
);

-- Every terminal/retry path already updates AgentRun in its own transaction.
create function sync_chat_run_progress() returns trigger language plpgsql as $$
begin
  if new.origin = 'CHAT' and new.status is distinct from old.status then
    if new.status in ('SUCCEEDED', 'FAILED', 'QUEUED') then
      update chat_run_progress
      set phase = case new.status when 'SUCCEEDED' then 'COMPLETE' when 'FAILED' then 'FAILED' else 'QUEUED' end,
          epoch = epoch + 1, revision = revision + 1,
          response_text = case when new.status = 'QUEUED' then '' else response_text end,
          updated_at = now()
      where workspace_id = new.workspace_id and agent_run_id = new.id;
    end if;
  end if;
  return new;
end $$;
create trigger chat_run_progress_status after update of status on agent_runs
  for each row execute function sync_chat_run_progress();
