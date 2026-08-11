alter table routines
  drop constraint routines_cadence_check;

alter table routines
  add constraint routines_cadence_check
  check (cadence in ('DAILY', 'WEEKLY', 'ON_GITHUB_CHANGE'));

drop index routines_due_idx;

create index routines_due_idx
  on routines(next_run_at, created_at)
  where enabled = true and cadence <> 'ON_GITHUB_CHANGE';
