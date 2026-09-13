-- V53: Strict constraints on active runs and disposition validation

-- 1. At most one active response version per turn
create unique index chat_response_versions_single_active_idx
  on chat_response_versions(workspace_id, turn_id)
  where (is_active = true);

-- 2. Disposition validation on legacy activity provenance
alter table legacy_activity_provenance
  add constraint legacy_activity_provenance_disposition_check
    check (disposition in ('NO_GENERATION', 'ADMITTED', 'EXCLUDED', 'REJECTED'));
