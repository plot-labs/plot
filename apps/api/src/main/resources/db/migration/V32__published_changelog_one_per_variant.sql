create unique index published_changelog_one_per_variant_idx
  on published_changelog_entries(workspace_id, content_variant_id);
