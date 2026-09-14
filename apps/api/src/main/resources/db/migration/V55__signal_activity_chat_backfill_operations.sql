-- V55: Durable operator-run backfill leases and reconciliation metrics.
--
-- V52 owns the checkpoint identity and fixed high-water mark. These additive
-- fields make an interrupted, bounded operator run observable and reclaimable.

alter table signal_activity_chat_backfill_checkpoints
  add column attempted_count bigint not null default 0,
  add column inserted_count bigint not null default 0,
  add column skipped_count bigint not null default 0,
  add column lease_owner varchar(64),
  add column lease_expires_at timestamptz;

create index signal_activity_chat_backfill_checkpoint_lease_idx
  on signal_activity_chat_backfill_checkpoints(lease_expires_at)
  where lease_expires_at is not null;
