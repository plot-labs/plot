alter table agent_runs
  drop constraint agent_runs_content_type_check;

alter table agent_runs
  add constraint agent_runs_content_type_check
  check (content_type in ('ARTIFACT', 'CHANGELOG', 'LAUNCH_ANNOUNCEMENT'));
