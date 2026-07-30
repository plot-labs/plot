create table github_webhook_deliveries (
  id uuid primary key,
  external_delivery_id text not null unique,
  event_type varchar not null,
  event_action varchar,
  installation_id bigint,
  repository_id bigint,
  ref text,
  before_sha varchar(40),
  after_sha varchar(40),
  tag_name text,
  ref_created boolean,
  ref_deleted boolean,
  forced boolean,
  payload_hash varchar(64) not null,
  disposition varchar not null,
  error_code varchar,
  received_at timestamptz not null,
  processed_at timestamptz,
  check (length(trim(external_delivery_id)) > 0),
  check (length(payload_hash) = 64),
  check (disposition in ('RECEIVED', 'OBSERVED', 'QUEUED', 'IGNORED', 'FAILED'))
);

create index github_webhook_deliveries_processing_idx
  on github_webhook_deliveries(disposition, received_at)
  where disposition = 'RECEIVED';

create table github_release_draft_requests (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_scope_id uuid not null,
  initial_delivery_id uuid not null references github_webhook_deliveries(id),
  tag_name text not null,
  observed_head_sha varchar(40),
  base_sha varchar(40),
  head_sha varchar(40),
  boundary_reason varchar,
  status varchar not null,
  attempt_count integer not null default 0,
  generation_attempt integer not null default 0,
  transition_version bigint not null default 0,
  claimed_by text,
  claimed_at timestamptz,
  heartbeat_at timestamptz,
  next_attempt_at timestamptz,
  generation_run_id uuid,
  observation_id uuid,
  error_code varchar,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  finished_at timestamptz,
  unique (workspace_id, id),
  unique (workspace_id, source_scope_id, tag_name),
  unique (generation_run_id),
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  foreign key (workspace_id, observation_id, source_scope_id)
    references source_observations(workspace_id, id, source_scope_id) on delete restrict,
  check (length(trim(tag_name)) > 0),
  check (status in ('QUEUED', 'RESOLVING', 'GENERATING', 'READY',
    'NO_ACTIVITY', 'NEEDS_RANGE', 'FAILED')),
  check (attempt_count >= 0),
  check (generation_attempt >= 0),
  check (transition_version >= 0),
  check ((claimed_by is null and claimed_at is null and heartbeat_at is null)
    or (claimed_by is not null and claimed_at is not null)),
  check (finished_at is null or finished_at >= created_at)
);

create index github_release_draft_requests_runnable_idx
  on github_release_draft_requests(status, next_attempt_at, created_at)
  where status in ('QUEUED', 'RESOLVING');

create index github_release_draft_requests_reconcile_idx
  on github_release_draft_requests(status, updated_at)
  where status = 'GENERATING';

create index github_release_draft_requests_stale_claim_idx
  on github_release_draft_requests(heartbeat_at)
  where claimed_by is not null;

create table github_release_draft_evidence (
  request_id uuid not null,
  workspace_id uuid not null,
  observation_id uuid not null,
  writing_block_id uuid not null,
  order_index integer not null,
  primary key (request_id, order_index),
  unique (request_id, writing_block_id),
  foreign key (workspace_id, request_id)
    references github_release_draft_requests(workspace_id, id) on delete cascade,
  foreign key (workspace_id, writing_block_id)
    references writing_blocks(workspace_id, id) on delete restrict,
  foreign key (workspace_id, observation_id)
    references source_observations(workspace_id, id) on delete restrict,
  check (order_index >= 0)
);

create table github_release_generation_attempts (
  request_id uuid not null,
  workspace_id uuid not null,
  attempt_no integer not null,
  generation_run_id uuid not null,
  created_at timestamptz not null,
  primary key (request_id, attempt_no),
  unique (generation_run_id),
  foreign key (workspace_id, request_id)
    references github_release_draft_requests(workspace_id, id) on delete cascade,
  foreign key (workspace_id, generation_run_id)
    references generation_runs(workspace_id, id) on delete restrict,
  check (attempt_no >= 0)
);

alter table content_packs
  add column release_request_id uuid;

alter table content_packs
  add foreign key (workspace_id, release_request_id)
    references github_release_draft_requests(workspace_id, id) on delete restrict;

create unique index content_packs_one_per_release_request_idx
  on content_packs(workspace_id, release_request_id)
  where release_request_id is not null;
