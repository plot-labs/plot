alter table workspaces
  drop constraint workspaces_entitlement_access_check;

update workspaces
set access_mode = 'full'
where entitlement_status in ('subscription_required', 'revoked')
  and access_mode = 'read_only';

alter table workspaces
  alter column access_mode set default 'full',
  add constraint workspaces_entitlement_access_check
    check (access_mode in ('full', 'read_only', 'complete_only'));
