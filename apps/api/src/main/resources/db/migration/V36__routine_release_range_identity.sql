alter table github_release_draft_requests
  add column routine_id uuid,
  add constraint github_release_routine_source_fk
    foreign key (workspace_id, routine_id, source_scope_id)
    references routines(workspace_id, id, source_scope_id) on delete restrict,
  add constraint github_release_routine_identity_key unique (workspace_id, id, routine_id);

alter table github_release_draft_requests
  drop constraint github_release_draft_requests_workspace_id_source_scope_id__key;

create unique index github_release_default_identity_idx
  on github_release_draft_requests(workspace_id, source_scope_id, tag_name)
  where routine_id is null;
create unique index github_release_routine_identity_idx
  on github_release_draft_requests(workspace_id, source_scope_id, tag_name, routine_id)
  where routine_id is not null;

alter table routine_executions
  add column release_request_id uuid,
  add constraint routine_execution_release_owner_fk
    foreign key (workspace_id, release_request_id, routine_id)
    references github_release_draft_requests(workspace_id, id, routine_id) on delete restrict,
  add constraint routine_execution_release_trigger_check
    check (release_request_id is null or trigger_kind = 'GITHUB');

create index routine_executions_release_request_idx
  on routine_executions(workspace_id, release_request_id) where release_request_id is not null;
