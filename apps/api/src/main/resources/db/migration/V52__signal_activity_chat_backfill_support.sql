-- V52: Backfill support and active version tracking

-- 1. Add is_active flag to chat_response_versions
alter table chat_response_versions
  add column is_active boolean not null default false;

-- 2. Durable backfill checkpoints table
create table signal_activity_chat_backfill_checkpoints (
  checkpoint_key varchar(64) primary key,
  high_water_mark timestamptz not null,
  last_processed_id uuid,
  status varchar(32) not null default 'IN_PROGRESS',
  reconciled_count bigint not null default 0,
  lag_count bigint not null default 0,
  updated_at timestamptz not null default now()
);

-- 3. Indexes for backfill scanning
create index if not exists agent_runs_backfill_idx on agent_runs(created_at, id);
create index if not exists autonomy_signals_backfill_idx on autonomy_signals(received_at, id);
create index if not exists autonomy_opportunities_backfill_idx on autonomy_opportunities(created_at, id);
