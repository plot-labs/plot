create table published_changelog_entries (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  content_variant_id uuid not null,
  artifact_revision_id uuid not null,
  artifact_revision_number integer not null,
  entry_slug text not null,
  title text not null,
  body_markdown text not null,
  tag_name text,
  published_by_user_id uuid references users(id),
  published_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, entry_slug)
);

create unique index published_changelog_one_per_tag_idx
  on published_changelog_entries(workspace_id, tag_name)
  where tag_name is not null;

create index published_changelog_workspace_published_at_idx
  on published_changelog_entries(workspace_id, published_at desc);
