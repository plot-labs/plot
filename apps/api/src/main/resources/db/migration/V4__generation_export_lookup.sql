create index generation_export_events_successful_markdown_lookup_idx
  on generation_export_events (
    workspace_id,
    generation_run_id,
    content_variant_id,
    disposition,
    unresolved_count,
    warning_acknowledged,
    output_content_hash,
    created_by_user_id,
    created_at,
    id
  )
  where format = 'MARKDOWN' and status = 'SUCCEEDED';
