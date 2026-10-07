-- Launch announcements live on only as the system skill; the separate content type is retired.
update agent_runs
set content_type = 'ARTIFACT'
where content_type = 'LAUNCH_ANNOUNCEMENT';

alter table agent_runs
  drop constraint agent_runs_content_type_check;

alter table agent_runs
  add constraint agent_runs_content_type_check
  check (content_type in ('ARTIFACT', 'CHANGELOG'));
