create table github_installation_states (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  user_id uuid not null references users(id),
  nonce_hash text not null unique,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null,
  unique (workspace_id, id),
  check (expires_at > created_at)
);

create index github_installation_states_expiry_idx
  on github_installation_states(expires_at)
  where consumed_at is null;

create table connections (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  provider varchar not null,
  connection_kind varchar not null,
  external_connection_key text not null,
  external_account_login text,
  permissions jsonb,
  status varchar not null,
  created_by_user_id uuid references users(id),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, provider),
  unique (workspace_id, provider, external_connection_key),
	check (length(trim(external_connection_key)) > 0),
  check (length(trim(provider)) > 0),
  check (length(trim(connection_kind)) > 0),
  check (status in ('ACTIVE', 'NEEDS_REAUTH', 'DISABLED', 'ERROR'))
);

create table source_namespaces (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  provider varchar not null,
  namespace_kind varchar not null,
  external_namespace_key text not null,
  display_name text,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, provider),
  unique (workspace_id, provider, namespace_kind, external_namespace_key),
  check (length(trim(external_namespace_key)) > 0),
  check (status in ('ACTIVE', 'DISABLED', 'ERROR'))
);

create table connection_namespace_bindings (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  provider varchar not null,
  connection_id uuid not null,
  source_namespace_id uuid not null,
  external_principal_id text,
  capabilities jsonb,
  status varchar not null,
  valid_from timestamptz not null,
  valid_to timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, connection_id, source_namespace_id),
  foreign key (workspace_id, connection_id, provider)
    references connections(workspace_id, id, provider) on delete restrict,
  foreign key (workspace_id, source_namespace_id, provider)
    references source_namespaces(workspace_id, id, provider) on delete restrict,
  check (status in ('ACTIVE', 'DISABLED', 'REVOKED', 'ERROR')),
  check (valid_to is null or valid_to >= valid_from)
);

create unique index connection_namespace_bindings_one_active_idx
  on connection_namespace_bindings(workspace_id, provider, source_namespace_id)
  where status = 'ACTIVE';

create table source_scopes (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_namespace_id uuid not null,
  provider varchar not null,
  scope_semantics varchar not null,
  scope_kind varchar not null,
  external_scope_key text not null,
  external_key text,
  display_name text not null,
  url text,
  metadata jsonb,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, source_namespace_id, id),
  unique (workspace_id, source_namespace_id, scope_kind, external_scope_key),
  foreign key (workspace_id, source_namespace_id, provider)
    references source_namespaces(workspace_id, id, provider) on delete restrict,
  check (scope_semantics in ('CONTAINER', 'QUERY', 'CORPUS', 'BATCH')),
  check (status in ('ACTIVE', 'DISABLED', 'ERROR'))
);

create index source_scopes_workspace_status_idx on source_scopes(workspace_id, status);

create table source_observations (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_scope_id uuid not null,
  binding_id uuid,
  authority_owner text not null,
  coverage_key text not null,
  observation_mode varchar not null,
  generation bigint not null,
  status varchar not null,
  started_at timestamptz not null,
  completed_at timestamptz,
  created_at timestamptz not null,
  unique (workspace_id, id),
	unique (workspace_id, id, source_scope_id),
  unique (workspace_id, authority_owner, coverage_key, generation),
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  foreign key (workspace_id, binding_id)
    references connection_namespace_bindings(workspace_id, id) on delete restrict,
  check (observation_mode in ('PARTIAL', 'COMPLETE', 'REMOVAL')),
  check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
  check (generation >= 0),
  check (status = 'RUNNING' or completed_at is not null)
);

create table source_imports (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_scope_id uuid not null,
  observation_id uuid not null,
  from_instant timestamptz not null,
  to_instant timestamptz not null,
  status varchar not null,
  eligible_count integer not null default 0,
  block_created_count integer not null default 0,
  block_updated_count integer not null default 0,
  block_unchanged_count integer not null default 0,
  error_code varchar,
  error_message text,
  started_at timestamptz not null,
  completed_at timestamptz,
  created_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
	foreign key (workspace_id, observation_id, source_scope_id)
	  references source_observations(workspace_id, id, source_scope_id) on delete restrict,
  check (from_instant < to_instant),
  check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
  check (eligible_count >= 0 and block_created_count >= 0 and block_updated_count >= 0),
  check (block_unchanged_count >= 0),
  check (completed_at is null or completed_at >= started_at),
  check (status = 'RUNNING' or completed_at is not null)
);

create unique index source_imports_one_running_idx
  on source_imports(workspace_id, source_scope_id) where status = 'RUNNING';

create index source_imports_workspace_created_idx
  on source_imports(workspace_id, created_at desc);

alter table writing_blocks
  add column source_namespace_id uuid,
  add column external_object_key text;

alter table writing_blocks
  add constraint writing_blocks_source_namespace_fk
    foreign key (workspace_id, source_namespace_id)
    references source_namespaces(workspace_id, id) on delete restrict,
  add constraint writing_blocks_source_identity_consistency_ck
    check ((source_namespace_id is null and external_object_key is null)
      or (source_namespace_id is not null and external_object_key is not null));

create unique index writing_blocks_workspace_source_object_uk
  on writing_blocks(workspace_id, source_namespace_id, source_kind, external_object_key)
  where source_namespace_id is not null and external_object_key is not null;

create unique index writing_blocks_workspace_namespace_id_uk
  on writing_blocks(workspace_id, source_namespace_id, id);

create table writing_block_scopes (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_namespace_id uuid not null,
  writing_block_id uuid not null,
  source_scope_id uuid not null,
  membership_kind varchar not null,
  status varchar not null,
  first_seen_at timestamptz not null,
  last_seen_at timestamptz not null,
  last_observation_id uuid,
  unique (workspace_id, id),
  unique (workspace_id, writing_block_id, source_scope_id),
  foreign key (workspace_id, source_namespace_id, writing_block_id)
    references writing_blocks(workspace_id, source_namespace_id, id) on delete restrict,
  foreign key (workspace_id, source_namespace_id, source_scope_id)
    references source_scopes(workspace_id, source_namespace_id, id) on delete restrict,
	foreign key (workspace_id, last_observation_id, source_scope_id)
	  references source_observations(workspace_id, id, source_scope_id) on delete restrict,
  check (membership_kind in ('CONTAINED_IN', 'MATCHED_BY', 'OBSERVED_VIA')),
  check (status in ('ACTIVE', 'TOMBSTONED')),
  check (last_seen_at >= first_seen_at)
);

create index writing_block_scopes_scope_created_idx
  on writing_block_scopes(workspace_id, source_scope_id, last_seen_at desc, writing_block_id);

create table writing_block_fragments (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  writing_block_id uuid not null,
  fragment_kind varchar not null,
  external_fragment_key text not null,
  parent_fragment_id uuid,
  order_key text not null,
  body text,
  author text,
  source_created_at timestamptz,
  source_updated_at timestamptz,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, writing_block_id, id),
  unique (workspace_id, writing_block_id, fragment_kind, external_fragment_key),
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  foreign key (workspace_id, writing_block_id, parent_fragment_id)
    references writing_block_fragments(workspace_id, writing_block_id, id) on delete restrict,
  check (parent_fragment_id is null or parent_fragment_id <> id),
  check (status in ('ACTIVE', 'TOMBSTONED'))
);

create table writing_block_relations (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_block_id uuid not null,
  source_fragment_id uuid,
  relation_kind varchar not null,
  target_namespace_id uuid not null,
  target_kind varchar not null,
  target_external_object_key text not null,
  target_fragment_kind varchar,
  target_external_fragment_key text,
  target_block_id uuid,
  target_fragment_id uuid,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, source_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  foreign key (workspace_id, source_block_id, source_fragment_id)
    references writing_block_fragments(workspace_id, writing_block_id, id) on delete restrict,
  foreign key (workspace_id, target_namespace_id)
    references source_namespaces(workspace_id, id) on delete restrict,
  foreign key (workspace_id, target_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  foreign key (workspace_id, target_block_id, target_fragment_id)
    references writing_block_fragments(workspace_id, writing_block_id, id) on delete restrict,
  check ((target_fragment_kind is null) = (target_external_fragment_key is null)),
  check (target_fragment_id is null or target_block_id is not null),
  check (status in ('ACTIVE', 'TOMBSTONED'))
);

create unique index writing_block_relations_canonical_uk on writing_block_relations (
  workspace_id, source_block_id, coalesce(source_fragment_id, '00000000-0000-0000-0000-000000000000'::uuid),
  relation_kind, target_namespace_id, target_kind, target_external_object_key,
  coalesce(target_fragment_kind, ''), coalesce(target_external_fragment_key, '')
);

create table writing_block_relation_observations (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  relation_id uuid not null,
  observation_id uuid not null,
  status varchar not null,
  first_seen_at timestamptz not null,
  last_seen_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, relation_id, observation_id),
  foreign key (workspace_id, relation_id)
    references writing_block_relations(workspace_id, id) on delete restrict,
  foreign key (workspace_id, observation_id)
    references source_observations(workspace_id, id) on delete restrict,
  check (status in ('ACTIVE', 'TOMBSTONED')),
  check (last_seen_at >= first_seen_at)
);
