create table github_repository_monitoring (
  id uuid primary key,
  workspace_id uuid not null,
  source_scope_id uuid not null,
  monitoring_status varchar not null,
  analysis_status varchar not null,
  release_convention varchar,
  tag_prefix text,
  sample_source varchar,
  sample_size integer not null default 0,
  sample_truncated boolean not null default false,
  attempt_count integer not null default 0,
  transition_version bigint not null default 0,
  claimed_by text,
  claimed_at timestamptz,
  next_attempt_at timestamptz,
  last_error_code varchar,
  analyzed_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, source_scope_id),
  foreign key (workspace_id, source_scope_id)
    references source_scopes(workspace_id, id) on delete restrict,
  check (monitoring_status in ('ACTIVE', 'DISABLED')),
  check (analysis_status in ('QUEUED', 'ANALYZING', 'COMPLETED', 'FAILED')),
  check (release_convention is null or release_convention in
    ('SEMVER_V', 'SEMVER', 'PREFIXED', 'MIXED', 'NO_TAGS')),
  check (sample_source is null or sample_source in ('RELEASES', 'TAGS')),
  check (sample_size >= 0),
  check (attempt_count >= 0),
  check (transition_version >= 0),
  check (
    (analysis_status = 'ANALYZING' and claimed_by is not null and claimed_at is not null)
    or (analysis_status <> 'ANALYZING' and claimed_by is null and claimed_at is null)
  ),
  check (analysis_status <> 'COMPLETED' or release_convention is not null),
  check (
    (release_convention = 'PREFIXED' and length(trim(tag_prefix)) > 0)
    or (release_convention <> 'PREFIXED' and tag_prefix is null)
    or release_convention is null
  ),
  check (
    release_convention = 'NO_TAGS'
    or release_convention is null
    or sample_source is not null
  )
);

create index github_repository_monitoring_runnable_idx
  on github_repository_monitoring(next_attempt_at, created_at)
  where monitoring_status = 'ACTIVE' and analysis_status = 'QUEUED';

create index github_repository_monitoring_stale_claim_idx
  on github_repository_monitoring(claimed_at)
  where monitoring_status = 'ACTIVE' and analysis_status = 'ANALYZING';
