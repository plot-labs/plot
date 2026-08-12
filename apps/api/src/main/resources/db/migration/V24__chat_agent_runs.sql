alter table agent_runs
  add column origin varchar not null default 'ROUTINE',
  add column idempotency_key text,
  add column request_fingerprint text;

update agent_runs
set idempotency_key = 'routine:' || routine_execution_id,
    request_fingerprint = 'routine:' || routine_execution_id
where origin = 'ROUTINE';

alter table agent_runs
  alter column routine_execution_id drop not null,
  alter column routine_id drop not null,
  alter column idempotency_key set not null,
  alter column request_fingerprint set not null;

do $$
declare
  constraint_name text;
begin
  select conname
    into constraint_name
    from pg_constraint
   where conrelid = 'agent_runs'::regclass
     and contype = 'u'
     and pg_get_constraintdef(oid) = 'UNIQUE (workspace_id, work_session_id)';
  if constraint_name is not null then
    execute format('alter table agent_runs drop constraint %I', constraint_name);
  end if;
end $$;

alter table agent_runs
  add constraint agent_runs_origin_check
  check (
    (origin = 'ROUTINE' and routine_execution_id is not null and routine_id is not null)
    or (origin = 'CHAT' and routine_execution_id is null and routine_id is null)
  ),
  add constraint agent_runs_origin_value_check
  check (origin in ('ROUTINE', 'CHAT')),
  add constraint agent_runs_request_identity_check
  check (length(trim(idempotency_key)) > 0 and length(trim(request_fingerprint)) > 0);

create unique index agent_runs_chat_request_key_idx
  on agent_runs(workspace_id, idempotency_key)
  where origin = 'CHAT';

do $$
declare
  constraint_name text;
begin
  for constraint_name in
    select conname
      from pg_constraint
     where conrelid = 'agent_run_inputs'::regclass
       and contype = 'c'
       and pg_get_constraintdef(oid) like '%input_kind%'
  loop
    execute format('alter table agent_run_inputs drop constraint %I', constraint_name);
  end loop;
end $$;

alter table agent_run_inputs
  add constraint agent_run_inputs_kind_check
  check (
    (input_kind = 'SEED' and (
      (routine_id is not null and activity_sequence is not null)
      or (routine_id is null and activity_sequence is null)
    ))
    or (input_kind = 'TOOL_RESULT' and routine_id is null and activity_sequence is null)
  );
