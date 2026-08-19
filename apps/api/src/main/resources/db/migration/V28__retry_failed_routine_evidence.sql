drop index agent_run_inputs_seed_identity_idx;

create index agent_run_inputs_seed_identity_idx
  on agent_run_inputs(workspace_id, routine_id, writing_block_id, activity_sequence)
  where input_kind = 'SEED';
