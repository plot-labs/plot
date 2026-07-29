update generation_runs
set status = 'NEEDS_REVIEW',
    finished_at = coalesce(finished_at, updated_at, now())
where status = 'NEEDS_YOUR_CALL';

alter table generation_runs
  drop constraint generation_runs_status_check;

alter table generation_runs
  add constraint generation_runs_status_check
  check (status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING', 'READY',
    'NEEDS_REVIEW', 'FAILED'));

drop table generation_intervention_resolutions;
drop table generation_interventions;
drop table writing_block_relation_observations;
drop table writing_block_relations;
drop table writing_block_fragments;
drop table tasks;
