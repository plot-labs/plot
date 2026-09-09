-- Every not-yet-admitted release now uses customer-value assessment.
-- Keep existing execution and assessment records; do not replay completed drafts.
alter table github_release_draft_requests drop column autonomy_mode;
