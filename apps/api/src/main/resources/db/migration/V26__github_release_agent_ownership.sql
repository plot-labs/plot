alter table github_release_draft_requests
  add column agent_run_id uuid;

alter table github_release_draft_requests
  add constraint github_release_draft_agent_run_fk
  foreign key (workspace_id, agent_run_id)
  references agent_runs(workspace_id, id) on delete restrict;

create unique index github_release_draft_agent_run_idx
  on github_release_draft_requests(workspace_id, agent_run_id)
  where agent_run_id is not null;
