-- Keep existing deletion obligations immediately due; preserve old-code inserts.
alter table account_deletion_provider_cleanup
  add column next_attempt_at timestamptz not null default now(),
  add column attempt_count integer not null default 0 check (attempt_count >= 0),
  add column claim_token uuid,
  add column lease_until timestamptz,
  add constraint account_cleanup_lease_pair check ((claim_token is null) = (lease_until is null));

create index account_cleanup_next_attempt on account_deletion_provider_cleanup(next_attempt_at)
  where claim_token is null;
