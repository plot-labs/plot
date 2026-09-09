-- The inbox is itself the durable dispatch queue; no in-memory event is required.
create table autonomy_signals (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_namespace_id uuid not null,
  source_scope_id uuid not null,
  provider varchar not null,
  delivery_key text not null check (length(trim(delivery_key)) > 0),
  object_key text not null check (length(trim(object_key)) > 0),
  event_type text not null check (length(trim(event_type)) > 0),
  schema_version integer not null check (schema_version = 1),
  source_version timestamptz,
  tombstone boolean not null default false,
  payload jsonb not null check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 524288),
  check (not tombstone or source_version is not null),
  received_at timestamptz not null,
  state varchar not null default 'PENDING' check (state in ('PENDING','PROCESSING','RETRY_WAIT','SUCCEEDED','FAILED','SUPERSEDED')),
  attempts integer not null default 0 check (attempts >= 0),
  available_at timestamptz not null,
  claim_token uuid,
  lease_until timestamptz,
  last_error_code varchar(100),
  unique (workspace_id, id),
  unique (workspace_id, source_namespace_id, source_scope_id, delivery_key),
  foreign key (workspace_id, source_namespace_id, provider) references source_namespaces(workspace_id, id, provider),
  foreign key (workspace_id, source_namespace_id, source_scope_id) references source_scopes(workspace_id, source_namespace_id, id),
  check ((state = 'PROCESSING') = (claim_token is not null and lease_until is not null))
);
create index autonomy_signals_dispatch_idx on autonomy_signals(provider, available_at, received_at)
  where state in ('PENDING','RETRY_WAIT','PROCESSING');
-- Serializes updates of each provider object and prevents older edits resurrecting tombstones.
create table autonomy_signal_heads (
  workspace_id uuid not null,
  source_namespace_id uuid not null,
  source_scope_id uuid not null,
  object_key text not null,
  signal_id uuid not null,
  source_version timestamptz not null,
  tombstone boolean not null,
  primary key (workspace_id, source_namespace_id, source_scope_id, object_key),
  foreign key (workspace_id, signal_id) references autonomy_signals(workspace_id, id),
  foreign key (workspace_id, source_namespace_id, source_scope_id) references source_scopes(workspace_id, source_namespace_id, id)
);
