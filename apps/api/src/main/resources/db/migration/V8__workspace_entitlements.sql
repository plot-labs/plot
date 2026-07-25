alter table workspaces
  add column entitlement_status varchar not null default 'trialing',
  add column access_mode varchar not null default 'full',
  add column trial_started_at timestamptz not null default now(),
  add column trial_ends_at timestamptz not null default (now() + interval '30 days');

alter table workspaces
  add constraint workspaces_entitlement_status_check
    check (entitlement_status in ('trialing', 'active', 'expired', 'revoked')),
  add constraint workspaces_access_mode_check
    check (access_mode in ('full', 'read_only')),
  add constraint workspaces_plan_entitlement_check
    check (
      (plan = 'trial' and entitlement_status in ('trialing', 'expired'))
      or
      (plan = 'founding' and entitlement_status in ('active', 'revoked'))
    ),
  add constraint workspaces_entitlement_access_check
    check (
      (entitlement_status in ('trialing', 'active') and access_mode = 'full')
      or
      (entitlement_status in ('expired', 'revoked') and access_mode = 'read_only')
    );
