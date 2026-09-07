alter table published_changelog_entries
  add column unpublished_at timestamptz,
  add column unpublished_by_user_id uuid references users(id);

alter table published_changelog_entries
  drop constraint published_changelog_entries_workspace_id_entry_slug_key;

create unique index published_changelog_live_entry_slug_idx
  on published_changelog_entries(workspace_id, entry_slug)
  where unpublished_at is null;

drop index published_changelog_one_per_variant_idx;
create unique index published_changelog_live_one_per_variant_idx
  on published_changelog_entries(workspace_id, content_variant_id)
  where unpublished_at is null;

drop index published_changelog_one_per_tag_idx;
create unique index published_changelog_live_one_per_tag_idx
  on published_changelog_entries(workspace_id, tag_name)
  where tag_name is not null and unpublished_at is null;

create index published_changelog_live_workspace_published_at_idx
  on published_changelog_entries(workspace_id, published_at desc)
  where unpublished_at is null;
