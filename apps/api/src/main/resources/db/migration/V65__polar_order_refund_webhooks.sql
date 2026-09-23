alter table polar_webhook_events
  rename column subscription_id to resource_id;

alter table polar_webhook_events
  add column subscription_id text,
  add column order_id text,
  add column refund_id text,
  add column polar_customer_id text,
  add column polar_product_id text,
  add column checkout_id text,
  add column event_at timestamptz,
  add column resource_status varchar,
  add column amount bigint,
  add column refunded_amount bigint,
  add column currency varchar(3),
  add column billing_reason varchar,
  add column revoke_benefits boolean;

update polar_webhook_events
set subscription_id = resource_id
where event_type like 'subscription.%';

create index workspaces_polar_customer_id_idx
  on workspaces(polar_customer_id)
  where polar_customer_id is not null;
