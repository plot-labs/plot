create table users (
  id uuid primary key,
  email text not null unique,
  display_name text not null,
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table workspaces (
  id uuid primary key,
  name text not null,
  slug text not null unique,
  created_by_user_id uuid references users(id),
  status varchar not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table workspace_members (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  user_id uuid not null references users(id),
  role varchar not null,
  status varchar not null,
  joined_at timestamptz not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, user_id)
);

create table work_sessions (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  title text,
  status varchar not null,
  created_by_user_id uuid references users(id),
  last_activity_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id)
);

create table tasks (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  work_session_id uuid,
  title text not null,
  status varchar not null,
  created_by_user_id uuid references users(id),
  last_activity_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id),
  foreign key (workspace_id, work_session_id)
    references work_sessions(workspace_id, id)
);

create table writing_blocks (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  source_origin varchar not null,
  source_kind varchar not null,
  title text,
  body text,
  url text,
  canonical_url text,
  author text,
  platform varchar,
  metadata jsonb,
  content_hash text,
  source_created_at timestamptz,
  source_updated_at timestamptz,
  ingested_at timestamptz not null,
  status varchar not null,
  created_by_user_id uuid references users(id),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (workspace_id, id)
);

create index work_sessions_workspace_created_idx
  on work_sessions(workspace_id, created_at desc);

create index tasks_workspace_created_idx
  on tasks(workspace_id, created_at desc);

create index writing_blocks_workspace_created_idx
  on writing_blocks(workspace_id, created_at desc);

create index writing_blocks_workspace_content_hash_idx
  on writing_blocks(workspace_id, content_hash);
