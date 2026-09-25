-- Account access is removed in one local transaction. WorkOS resources are
-- removed afterward and retried until both the user and owned organizations
-- are gone.
create table account_deletion_provider_cleanup (
  workos_user_id text primary key,
  created_at timestamptz not null default now(),
  last_attempt_at timestamptz,
  last_error text
);

create table account_deletion_provider_organizations (
  workos_user_id text not null references account_deletion_provider_cleanup(workos_user_id) on delete cascade,
  workos_organization_id text not null,
  primary key (workos_user_id, workos_organization_id)
);
