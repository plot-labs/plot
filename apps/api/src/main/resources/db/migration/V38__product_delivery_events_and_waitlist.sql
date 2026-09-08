create table waitlist_signups (
  id uuid primary key,
  email text not null,
  role text,
  pain_channel text not null,
  company text,
  resend_contact_id text,
  created_at timestamptz not null default now(),
  constraint waitlist_signups_email_key unique (email),
  constraint waitlist_signups_pain_channel_check check (
    pain_channel in ('docs', 'changelog', 'customer_updates', 'launch_social', 'other')
  ),
  constraint waitlist_signups_role_check check (
    role is null or role in ('founder', 'engineering', 'product', 'devrel', 'other')
  ),
  constraint waitlist_signups_company_length_check check (
    company is null or char_length(company) <= 200
  )
);

create index waitlist_signups_created_at_idx on waitlist_signups (created_at desc);

create table product_delivery_events (
  id uuid primary key,
  workspace_id uuid not null references workspaces(id),
  kind text not null,
  generation_export_event_id uuid,
  published_changelog_entry_id uuid,
  content_variant_id uuid not null,
  artifact_revision_id uuid not null,
  created_by_user_id uuid not null references users(id),
  client_event_id uuid,
  created_at timestamptz not null default now(),
  constraint product_delivery_events_workspace_id_id_key unique (workspace_id, id),
  constraint product_delivery_events_kind_check check (
    kind in (
      'CLIPBOARD_WRITE_SUCCEEDED',
      'CLIPBOARD_WRITE_FAILED',
      'DOWNLOAD_STARTED',
      'PUBLISHED',
      'EXTERNAL_DELIVERY_CONFIRMED'
    )
  ),
  constraint product_delivery_events_refs_check check (
    (
      kind in ('CLIPBOARD_WRITE_SUCCEEDED', 'CLIPBOARD_WRITE_FAILED', 'DOWNLOAD_STARTED')
      and generation_export_event_id is not null
      and published_changelog_entry_id is null
    )
    or (
      kind in ('PUBLISHED', 'EXTERNAL_DELIVERY_CONFIRMED')
      and published_changelog_entry_id is not null
      and generation_export_event_id is null
    )
  ),
  constraint product_delivery_events_export_fk foreign key (workspace_id, generation_export_event_id)
    references generation_export_events (workspace_id, id),
  constraint product_delivery_events_published_fk foreign key (workspace_id, published_changelog_entry_id)
    references published_changelog_entries (workspace_id, id)
);

create unique index product_delivery_events_client_event_id_uidx
  on product_delivery_events (client_event_id)
  where client_event_id is not null;

create unique index product_delivery_events_export_kind_user_uidx
  on product_delivery_events (generation_export_event_id, kind, created_by_user_id)
  where generation_export_event_id is not null;

create unique index product_delivery_events_published_kind_user_uidx
  on product_delivery_events (published_changelog_entry_id, kind, created_by_user_id)
  where kind = 'EXTERNAL_DELIVERY_CONFIRMED';

create unique index product_delivery_events_published_once_uidx
  on product_delivery_events (published_changelog_entry_id, kind)
  where kind = 'PUBLISHED';

create index product_delivery_events_workspace_created_at_idx
  on product_delivery_events (workspace_id, created_at desc);

create trigger product_delivery_events_append_only
  before update or delete on product_delivery_events
  for each row execute function reject_generation_append_only_mutation();
