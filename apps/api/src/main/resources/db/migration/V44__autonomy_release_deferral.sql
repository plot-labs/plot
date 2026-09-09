alter table github_release_draft_requests drop constraint github_release_draft_requests_status_check;
alter table github_release_draft_requests add constraint github_release_draft_requests_status_check
  check (status in ('QUEUED','RESOLVING','GENERATING','READY','NO_ACTIVITY','NEEDS_RANGE','DEFERRED','FAILED'));
alter table github_release_draft_requests add column autonomy_mode varchar;
update github_release_draft_requests set autonomy_mode = 'OFF';
alter table github_release_draft_requests add constraint release_autonomy_mode_check
  check (autonomy_mode in ('OFF','SHADOW','ACTIVE'));
alter table routine_executions drop constraint routine_executions_status_check;
alter table routine_executions add constraint routine_executions_status_check
  check (status in ('PROBING','NO_ACTIVITY','DISPATCHED','DEFERRED','FAILED'));
alter table routines drop constraint routines_last_run_status_check;
alter table routines add constraint routines_last_run_status_check check (last_run_status is null or last_run_status in
  ('NO_ACTIVITY','QUEUED','WRITING','REVIEWING','REWRITING','READY','NEEDS_REVIEW','FAILED','DEFERRED'));
