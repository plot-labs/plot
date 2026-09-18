create table agent_model_invocations (
  id uuid primary key,
  workspace_id uuid not null,
  agent_run_id uuid not null,
  sequence_no integer not null,
  status varchar not null,
  provider varchar,
  requested_model text,
  actual_model text,
  provider_response_id text,
  input_token_count bigint,
  output_token_count bigint,
  cache_read_token_count bigint,
  cache_write_token_count bigint,
  reasoning_token_count bigint,
  total_token_count bigint,
  provider_cost_usd numeric(24, 12),
  credits bigint,
  billing_basis varchar,
  price_policy_version varchar,
  created_at timestamptz not null,
  usage_recorded_at timestamptz,
  settled_at timestamptz,
  unique (workspace_id, id),
  unique (workspace_id, agent_run_id, sequence_no),
  foreign key (workspace_id, agent_run_id) references agent_runs(workspace_id, id) on delete restrict,
  check (sequence_no > 0),
  check (status in ('STARTED', 'PENDING', 'SETTLED', 'ABORTED', 'USAGE_UNKNOWN')),
  check (credits is null or credits > 0),
  check (input_token_count is null or input_token_count >= 0),
  check (output_token_count is null or output_token_count >= 0),
  check (cache_read_token_count is null or cache_read_token_count >= 0),
  check (cache_write_token_count is null or cache_write_token_count >= 0),
  check (reasoning_token_count is null or reasoning_token_count >= 0),
  check (total_token_count is null or total_token_count >= 0)
);

create unique index agent_model_invocations_workspace_unresolved_uk
  on agent_model_invocations(workspace_id)
  where status in ('STARTED', 'PENDING');

alter table model_invocations
  add column billing_status varchar,
  add column actual_model text,
  add column cache_read_token_count bigint,
  add column cache_write_token_count bigint,
  add column reasoning_token_count bigint,
  add column provider_cost_usd numeric(24, 12),
  add column credits bigint,
  add column billing_basis varchar,
  add column price_policy_version varchar,
  add column billing_settled_at timestamptz;

alter table model_invocations
  add constraint model_invocations_billing_status_check
    check (billing_status is null or billing_status in ('PENDING', 'SETTLED', 'USAGE_UNKNOWN')),
  add constraint model_invocations_credits_check check (credits is null or credits > 0),
  add constraint model_invocations_cache_read_check check (cache_read_token_count is null or cache_read_token_count >= 0),
  add constraint model_invocations_cache_write_check check (cache_write_token_count is null or cache_write_token_count >= 0),
  add constraint model_invocations_reasoning_check check (reasoning_token_count is null or reasoning_token_count >= 0);

create unique index model_invocations_workspace_billing_pending_uk
  on model_invocations(workspace_id)
  where billing_status = 'PENDING';
