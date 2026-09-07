alter table agent_runs
  add column content_type varchar not null default 'CHANGELOG';

alter table agent_runs
  add constraint agent_runs_content_type_check
  check (content_type in ('CHANGELOG', 'LAUNCH_ANNOUNCEMENT'));
