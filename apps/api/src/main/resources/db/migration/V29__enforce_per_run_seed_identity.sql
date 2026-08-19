create unique index agent_run_inputs_seed_per_run_idx
  on agent_run_inputs(workspace_id, agent_run_id, writing_block_id, activity_sequence)
  where input_kind = 'SEED';
