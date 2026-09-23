alter table workspaces
  drop constraint workspaces_plan_check,
  drop constraint workspaces_entitlement_status_check,
  drop constraint workspaces_plan_entitlement_check,
  drop constraint workspaces_entitlement_access_check;

update workspaces
set plan = 'none',
    entitlement_status = 'subscription_required',
    access_mode = 'read_only'
where plan = 'trial';

alter table workspaces
  alter column plan set default 'none',
  alter column entitlement_status set default 'subscription_required',
  alter column access_mode set default 'read_only',
  alter column trial_started_at set default now(),
  alter column trial_ends_at set default now(),
  add constraint workspaces_plan_check
    check (plan in ('none', 'founding')),
  add constraint workspaces_entitlement_status_check
    check (entitlement_status in ('subscription_required', 'active', 'revoked')),
  add constraint workspaces_plan_entitlement_check
    check (
      (plan = 'none' and entitlement_status = 'subscription_required')
      or
      (plan = 'founding' and entitlement_status in ('active', 'revoked'))
    ),
  add constraint workspaces_entitlement_access_check
    check (
      (entitlement_status = 'active' and access_mode = 'full')
      or
      (entitlement_status in ('subscription_required', 'revoked') and access_mode = 'read_only')
    );
