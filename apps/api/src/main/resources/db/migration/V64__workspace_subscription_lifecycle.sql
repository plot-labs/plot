alter table workspaces
  add column polar_subscription_status varchar,
  add column polar_subscription_cancel_at_period_end boolean,
  add column polar_subscription_current_period_end timestamptz,
  add column polar_subscription_event_at timestamptz;
