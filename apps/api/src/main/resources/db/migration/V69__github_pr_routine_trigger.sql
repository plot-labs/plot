alter table routines drop constraint routines_cadence_check;

alter table routines add constraint routines_cadence_check
  check (cadence in (
    'DAILY', 'WEEKLY', 'ON_GITHUB_CHANGE', 'ON_GITHUB_PR_MERGED',
    'ON_GITHUB_RELEASE', 'ON_GIT_TAG'
  ));

alter table github_webhook_deliveries
  add column pr_id bigint,
  add column pr_number bigint,
  add column pr_base_repository_id bigint,
  add column pr_base_branch text,
  add column pr_merge_commit_sha varchar(40),
  add column pr_merged boolean;
