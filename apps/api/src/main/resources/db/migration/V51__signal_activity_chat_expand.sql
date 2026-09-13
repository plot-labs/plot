-- V51__signal_activity_chat_expand.sql
-- Additive compatibility schema for Signal evaluation, logical Chat turns, response versions, execution envelopes, and legacy Activity provenance.

-- 1. Chat logical turns
create table chat_turns (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  work_session_id uuid not null,
  turn_index integer not null check (turn_index >= 0),
  user_message text not null,
  created_by_user_id uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id, id),
  unique (workspace_id, work_session_id, turn_index),
  foreign key (workspace_id, work_session_id) references work_sessions(workspace_id, id) on delete cascade
);

create index chat_turns_session_idx on chat_turns(workspace_id, work_session_id, turn_index);

-- 2. Chat response versions
create table chat_response_versions (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  turn_id uuid not null,
  version_index integer not null check (version_index >= 0),
  agent_run_id uuid not null,
  initiator_user_id uuid references users(id) on delete set null,
  lineage_parent_version_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id, id),
  unique (workspace_id, turn_id, version_index),
  unique (workspace_id, agent_run_id),
  foreign key (workspace_id, turn_id) references chat_turns(workspace_id, id) on delete cascade,
  foreign key (workspace_id, agent_run_id) references agent_runs(workspace_id, id) on delete cascade,
  foreign key (workspace_id, lineage_parent_version_id) references chat_response_versions(workspace_id, id) on delete set null
);

create index chat_response_versions_turn_idx on chat_response_versions(workspace_id, turn_id, version_index);

-- 3. Signal evaluation evidence
create table signal_evaluations (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  signal_id uuid not null,
  source_namespace_id uuid not null,
  source_scope_id uuid not null,
  input_fingerprint varchar(64) not null,
  outcome varchar not null check (outcome in ('NO_GENERATION', 'ADMITTED')),
  reason text not null,
  semantic_time timestamptz not null,
  admitted_response_version_id uuid,
  claim_token uuid,
  lease_until timestamptz,
  attempts integer not null default 0 check (attempts >= 0),
  last_error_code varchar(100),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id, id),
  unique (workspace_id, signal_id),
  foreign key (workspace_id, signal_id) references autonomy_signals(workspace_id, id) on delete cascade,
  foreign key (workspace_id, source_namespace_id, source_scope_id) references source_scopes(workspace_id, source_namespace_id, id) on delete cascade,
  foreign key (workspace_id, admitted_response_version_id) references chat_response_versions(workspace_id, id) on delete set null
);

create index signal_evaluations_workspace_time_idx on signal_evaluations(workspace_id, semantic_time desc);

-- 4. Frozen execution envelopes
create table chat_execution_envelopes (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  agent_run_id uuid not null,
  fingerprint_version integer not null default 1,
  envelope_fingerprint varchar(64) not null,
  generation_settings jsonb not null,
  source_snapshot_id uuid,
  created_at timestamptz not null default now(),
  unique (workspace_id, id),
  unique (workspace_id, agent_run_id),
  foreign key (workspace_id, agent_run_id) references agent_runs(workspace_id, id) on delete cascade,
  foreign key (workspace_id, source_snapshot_id) references content_source_snapshots(workspace_id, id) on delete set null
);

-- 5. Tool transcript entries
create table chat_execution_transcript_entries (
  id uuid primary key,
  workspace_id uuid not null,
  envelope_id uuid not null,
  call_index integer not null check (call_index >= 0),
  tool_name varchar not null,
  normalized_arguments jsonb not null,
  bounded_result jsonb not null,
  adopted_input_hash text,
  created_at timestamptz not null default now(),
  unique (workspace_id, id),
  unique (workspace_id, envelope_id, call_index),
  foreign key (workspace_id, envelope_id) references chat_execution_envelopes(workspace_id, id) on delete cascade
);

create index chat_execution_transcript_entries_idx on chat_execution_transcript_entries(workspace_id, envelope_id, call_index);

-- 6. Detached legacy activity provenance
create table legacy_activity_provenance (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  source_scope_id uuid not null,
  opportunity_id uuid,
  goal_id uuid,
  task_id uuid,
  agent_run_id uuid,
  chat_id uuid,
  title text not null,
  disposition varchar not null,
  reason text not null,
  dismissed boolean not null default false,
  missing_facts jsonb not null default '[]',
  last_error_code varchar(100),
  has_exact_signal_link boolean not null default false,
  uncertainty_label text,
  semantic_time timestamptz not null,
  created_at timestamptz not null default now(),
  unique (workspace_id, id),
  foreign key (workspace_id, source_scope_id) references source_scopes(workspace_id, id) on delete cascade
);

create index legacy_activity_provenance_time_idx on legacy_activity_provenance(workspace_id, semantic_time desc);
