alter table model_invocations
  add column attempt_no integer;

update model_invocations
set attempt_no = 1
where attempt_no is null;

alter table model_invocations
  alter column attempt_no set not null,
  alter column attempt_no set default 1,
  add constraint model_invocations_attempt_no_check check (attempt_no > 0);

alter table model_invocations
  drop constraint model_invocations_workspace_id_generation_run_id_logical_ca_key;

alter table model_invocations
  add constraint model_invocations_logical_attempt_uk
  unique (workspace_id, generation_run_id, logical_call_index, attempt_no);
