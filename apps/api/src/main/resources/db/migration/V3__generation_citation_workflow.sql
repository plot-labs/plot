create table generation_runs (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_scope_id uuid,
  created_by_user_id uuid not null references users(id),
  idempotency_key text not null,
  request_fingerprint text not null,
  status varchar not null,
  workflow_version text not null,
  prompt_version text not null,
  output_schema_version text not null,
  budget_version text not null,
  provider varchar not null,
  model_name text not null,
  budget_snapshot jsonb not null,
  user_instruction text,
  semantic_rewrite_attempt integer not null default 0,
  transition_version bigint not null default 0,
  claimed_by text,
  claimed_at timestamptz,
  heartbeat_at timestamptz,
  next_attempt_at timestamptz,
  error_code text,
  error_detail jsonb,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  check (length(trim(idempotency_key)) > 0),
  check (length(trim(request_fingerprint)) > 0),
  check (length(trim(workflow_version)) > 0),
  check (length(trim(prompt_version)) > 0),
  check (length(trim(output_schema_version)) > 0),
  check (length(trim(budget_version)) > 0),
  check (length(trim(provider)) > 0),
  check (length(trim(model_name)) > 0),
  check (status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING', 'READY',
    'NEEDS_YOUR_CALL', 'NEEDS_REVIEW', 'FAILED')),
  check (semantic_rewrite_attempt between 0 and 3),
  check (transition_version >= 0),
  check ((claimed_by is null and claimed_at is null and heartbeat_at is null)
    or (claimed_by is not null and claimed_at is not null)),
  check (finished_at is null or finished_at >= coalesce(started_at, created_at)),
  check ((status in ('READY', 'NEEDS_REVIEW', 'FAILED') and finished_at is not null)
    or (status not in ('READY', 'NEEDS_REVIEW', 'FAILED')))
);

create unique index generation_runs_idempotency_key_uk
  on generation_runs(workspace_id, created_by_user_id, idempotency_key);

create index generation_runs_runnable_idx
  on generation_runs(status, next_attempt_at, created_at)
  where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING');

create index generation_runs_stale_claim_idx
  on generation_runs(heartbeat_at)
  where claimed_by is not null;

create table generation_inputs (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  writing_block_id uuid not null,
  order_index integer not null,
  source_provider varchar not null,
  source_kind varchar not null,
  source_label text not null,
  snapshot_title text,
  snapshot_body text not null,
  snapshot_excerpt text,
  original_url text not null,
  source_created_at timestamptz,
  source_updated_at timestamptz,
  content_hash text not null,
  captured_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  unique (workspace_id, generation_run_id, order_index),
  unique (workspace_id, generation_run_id, writing_block_id),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  check (order_index >= 0),
  check (source_provider in ('GITHUB', 'SLACK', 'LINEAR')),
  check (length(trim(source_kind)) > 0),
  check (length(trim(source_label)) > 0),
  check (length(trim(snapshot_body)) > 0),
  check (length(trim(original_url)) > 0),
  check (length(trim(content_hash)) > 0)
);

create table generation_workflow_steps (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  step_kind varchar not null,
  sequence_no integer not null,
  semantic_attempt integer not null default 0,
  status varchar not null,
  failure_code text,
  failure_detail jsonb,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  unique (workspace_id, generation_run_id, sequence_no),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  check (step_kind in ('PREPARE_EVIDENCE', 'WRITER', 'REVIEWER', 'REWRITER',
    'CONFLICT_RESOLUTION', 'FINALIZE')),
  check (sequence_no >= 0),
  check (semantic_attempt between 0 and 3),
  check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  check (finished_at is null or started_at is null or finished_at >= started_at),
  check ((status in ('SUCCEEDED', 'FAILED') and finished_at is not null)
    or status in ('PENDING', 'RUNNING'))
);

create table model_invocations (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  workflow_step_id uuid not null,
  role varchar not null,
  logical_call_index integer not null,
  transport_attempts integer not null default 0,
  schema_attempts integer not null default 0,
  status varchar not null,
  provider varchar not null,
  model_name text not null,
  provider_request_id text,
  request_metadata jsonb not null default '{}'::jsonb,
  result_metadata jsonb,
  prompt_token_count integer,
  completion_token_count integer,
  total_token_count integer,
  latency_ms integer,
  failure_code text,
  failure_detail jsonb,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, generation_run_id, logical_call_index),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, workflow_step_id, generation_run_id)
    references generation_workflow_steps(workspace_id, id, generation_run_id) on delete restrict,
  check (role in ('WRITER', 'REVIEWER', 'REWRITER')),
  check (logical_call_index >= 0),
  check (transport_attempts >= 0),
  check (schema_attempts >= 0),
  check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  check (length(trim(provider)) > 0),
  check (length(trim(model_name)) > 0),
  check (prompt_token_count is null or prompt_token_count >= 0),
  check (completion_token_count is null or completion_token_count >= 0),
  check (total_token_count is null or total_token_count >= 0),
  check (latency_ms is null or latency_ms >= 0),
  check (finished_at is null or started_at is null or finished_at >= started_at),
  check ((status in ('SUCCEEDED', 'FAILED') and finished_at is not null)
    or status in ('PENDING', 'RUNNING'))
);

create table generation_artifacts (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  workflow_step_id uuid,
  artifact_type varchar not null,
  artifact_version integer not null,
  payload jsonb not null,
  content_hash text,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, generation_run_id, artifact_type, artifact_version),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, workflow_step_id, generation_run_id)
    references generation_workflow_steps(workspace_id, id, generation_run_id) on delete restrict,
  check (artifact_type in ('EVIDENCE_SET', 'WRITER_OUTPUT', 'REVIEWER_OUTPUT',
    'REWRITER_OUTPUT', 'CONFLICT_DECISION', 'FINAL_OUTPUT')),
  check (artifact_version > 0)
);

create table content_packs (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  title text,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  unique (workspace_id, generation_run_id),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  check (status in ('DRAFT', 'READY', 'NEEDS_REVIEW', 'ARCHIVED'))
);

create table content_variants (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  content_pack_id uuid not null,
  variant_index integer not null default 0,
  status varchar not null,
  title text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  unique (workspace_id, id, generation_run_id, content_pack_id),
  unique (workspace_id, content_pack_id, variant_index),
  foreign key (workspace_id, content_pack_id, generation_run_id)
    references content_packs(workspace_id, id, generation_run_id) on delete restrict,
  check (variant_index >= 0),
  check (status in ('DRAFT', 'READY', 'NEEDS_REVIEW', 'ARCHIVED'))
);

create table content_variant_sentences (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  content_variant_id uuid not null,
  stable_key text not null,
  order_index integer not null,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  unique (workspace_id, id, generation_run_id, content_variant_id),
  unique (workspace_id, content_variant_id, stable_key),
  unique (workspace_id, content_variant_id, order_index),
  foreign key (workspace_id, content_variant_id, generation_run_id)
    references content_variants(workspace_id, id, generation_run_id) on delete restrict,
  check (length(trim(stable_key)) > 0),
  check (order_index >= 0)
);

create table content_variant_sentence_revisions (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  content_variant_id uuid not null,
  sentence_id uuid not null,
  revision_no integer not null,
  origin varchar not null,
  body text not null,
  is_current boolean not null default true,
  created_by_user_id uuid references users(id),
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  unique (workspace_id, id, generation_run_id, sentence_id),
  unique (workspace_id, id, generation_run_id, content_variant_id, sentence_id),
  unique (workspace_id, sentence_id, revision_no),
  foreign key (workspace_id, sentence_id, generation_run_id, content_variant_id)
    references content_variant_sentences(workspace_id, id, generation_run_id, content_variant_id) on delete restrict,
  check (revision_no > 0),
  check (origin in ('GENERATED', 'REWRITTEN', 'USER_MODIFIED')),
  check (length(trim(body)) > 0),
  check ((origin = 'USER_MODIFIED' and created_by_user_id is not null)
    or origin <> 'USER_MODIFIED')
);

create unique index content_variant_sentence_one_current_idx
  on content_variant_sentence_revisions(workspace_id, sentence_id)
  where is_current;

create table sentence_evaluations (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  sentence_id uuid not null,
  sentence_revision_id uuid not null,
  review_attempt integer not null,
  verdict varchar not null,
  reason text,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, sentence_revision_id, review_attempt),
  foreign key (workspace_id, sentence_id, generation_run_id)
    references content_variant_sentences(workspace_id, id, generation_run_id) on delete restrict,
  foreign key (workspace_id, sentence_revision_id, generation_run_id, sentence_id)
    references content_variant_sentence_revisions(workspace_id, id, generation_run_id, sentence_id) on delete restrict,
  check (review_attempt > 0),
  check (verdict in ('SUPPORTED', 'NOT_REQUIRED', 'NEEDS_SUPPORT', 'CONFLICT')),
  check ((verdict in ('NEEDS_SUPPORT', 'CONFLICT') and length(trim(reason)) > 0)
    or verdict in ('SUPPORTED', 'NOT_REQUIRED'))
);

create table sentence_citations (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  content_variant_id uuid not null,
  sentence_id uuid not null,
  sentence_revision_id uuid not null,
  generation_input_id uuid not null,
  citation_order integer not null,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz,
  unique (workspace_id, id),
  unique (workspace_id, sentence_revision_id, generation_input_id),
  unique (workspace_id, sentence_revision_id, citation_order),
  foreign key (workspace_id, sentence_revision_id, generation_run_id, content_variant_id, sentence_id)
    references content_variant_sentence_revisions(workspace_id, id, generation_run_id, content_variant_id, sentence_id) on delete restrict,
  foreign key (workspace_id, generation_input_id, generation_run_id)
    references generation_inputs(workspace_id, id, generation_run_id) on delete restrict,
  check (citation_order >= 0),
  check (status in ('ACTIVE', 'STALE', 'REMOVED'))
);

create table generation_interventions (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  sentence_id uuid not null,
  kind varchar not null,
  status varchar not null,
  version bigint not null,
  conflict_detail jsonb,
  resolution_action varchar,
  resolved_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, sentence_id, generation_run_id)
    references content_variant_sentences(workspace_id, id, generation_run_id) on delete restrict,
  check (kind = 'SOURCE_CONFLICT'),
  check (status in ('PENDING', 'RESOLVED', 'CANCELLED')),
  check (version > 0),
  check (resolution_action is null or resolution_action in ('PREFER_SOURCE', 'OMIT_CLAIM', 'PROVIDE_WORDING')),
  check ((status = 'PENDING' and resolution_action is null and resolved_at is null)
    or (status = 'RESOLVED' and resolution_action is not null and resolved_at is not null)
    or (status = 'CANCELLED' and resolution_action is null and resolved_at is not null))
);

create unique index generation_interventions_one_pending_idx
  on generation_interventions(workspace_id, generation_run_id)
  where status = 'PENDING';

create table generation_intervention_resolutions (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  intervention_id uuid not null,
  version integer not null,
  action varchar not null,
  preferred_generation_input_id uuid,
  provided_wording text,
  decided_by_user_id uuid not null references users(id),
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, intervention_id, version),
  foreign key (workspace_id, intervention_id, generation_run_id)
    references generation_interventions(workspace_id, id, generation_run_id) on delete restrict,
  foreign key (workspace_id, preferred_generation_input_id, generation_run_id)
    references generation_inputs(workspace_id, id, generation_run_id) on delete restrict,
  check (version > 0),
  check (action in ('PREFER_SOURCE', 'OMIT_CLAIM', 'PROVIDE_WORDING')),
  check ((action = 'PREFER_SOURCE' and preferred_generation_input_id is not null and provided_wording is null)
    or (action = 'OMIT_CLAIM' and preferred_generation_input_id is null and provided_wording is null)
    or (action = 'PROVIDE_WORDING' and preferred_generation_input_id is null and length(trim(provided_wording)) > 0))
);

create table generation_export_events (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  content_variant_id uuid not null,
  format varchar not null,
  status varchar not null,
  unresolved_count integer not null,
  warning_acknowledged boolean not null,
  output_content_hash text,
  failure_code text,
  created_by_user_id uuid not null references users(id),
  created_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, content_variant_id, generation_run_id)
    references content_variants(workspace_id, id, generation_run_id) on delete restrict,
  check (format = 'MARKDOWN'),
  check (status in ('SUCCEEDED', 'REJECTED')),
  check (unresolved_count >= 0),
  check (status <> 'SUCCEEDED' or unresolved_count = 0 or warning_acknowledged)
);

create function reject_generation_append_only_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = '23514';
end;
$$;

create trigger generation_inputs_append_only
  before update or delete on generation_inputs
  for each row execute function reject_generation_append_only_mutation();

create trigger generation_artifacts_append_only
  before update or delete on generation_artifacts
  for each row execute function reject_generation_append_only_mutation();

create trigger sentence_evaluations_append_only
  before update or delete on sentence_evaluations
  for each row execute function reject_generation_append_only_mutation();

create trigger intervention_resolutions_append_only
  before update or delete on generation_intervention_resolutions
  for each row execute function reject_generation_append_only_mutation();

create trigger generation_export_events_append_only
  before update or delete on generation_export_events
  for each row execute function reject_generation_append_only_mutation();

create function protect_sentence_revision_history()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'DELETE' then
    raise exception '% is append-only', tg_table_name using errcode = '23514';
  end if;

  if new.id <> old.id
    or new.workspace_id <> old.workspace_id
    or new.generation_run_id <> old.generation_run_id
    or new.content_variant_id <> old.content_variant_id
    or new.sentence_id <> old.sentence_id
    or new.revision_no <> old.revision_no
    or new.origin <> old.origin
    or new.body <> old.body
    or new.created_by_user_id is distinct from old.created_by_user_id
    or new.created_at <> old.created_at
    or old.is_current = false
    or new.is_current = true then
    raise exception '% history is immutable', tg_table_name using errcode = '23514';
  end if;

  return new;
end;
$$;

create trigger content_variant_sentence_revisions_history
  before update or delete on content_variant_sentence_revisions
  for each row execute function protect_sentence_revision_history();
