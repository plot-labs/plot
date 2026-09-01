alter table published_changelog_entries
  add constraint published_changelog_entries_content_variant_fk
  foreign key (workspace_id, content_variant_id)
  references content_variants(workspace_id, id) on delete restrict;

alter table published_changelog_entries
  add constraint published_changelog_entries_artifact_revision_fk
  foreign key (workspace_id, artifact_revision_id)
  references content_variant_revisions(workspace_id, id) on delete restrict;
