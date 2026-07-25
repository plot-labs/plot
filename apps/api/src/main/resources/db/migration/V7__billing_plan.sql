alter table workspaces
  add column plan varchar not null default 'trial',
  add column polar_subscription_id text,
  add column polar_customer_id text,
  add column plan_updated_at timestamptz;

alter table workspaces
  add constraint workspaces_plan_check
  check (plan in ('trial', 'founding'));

create unique index workspaces_polar_subscription_uk
  on workspaces(polar_subscription_id)
  where polar_subscription_id is not null;

create table polar_webhook_events (
  webhook_id text primary key,
  event_type varchar not null,
  subscription_id text,
  received_at timestamptz not null,
  outcome varchar not null,
  matched_user_id uuid references users(id),
  matched_workspace_id uuid references workspaces(id)
);
