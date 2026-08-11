create sequence writing_block_activity_sequence;

alter table writing_blocks
  add column activity_sequence bigint;

with ordered_blocks as (
  select id, row_number() over (order by ingested_at, id) as activity_sequence
  from writing_blocks
)
update writing_blocks block
set activity_sequence = ordered.activity_sequence
from ordered_blocks ordered
where ordered.id = block.id;

select setval(
  'writing_block_activity_sequence',
  greatest(coalesce((select max(activity_sequence) from writing_blocks), 0), 1),
  exists(select 1 from writing_blocks)
);

alter table writing_blocks
  alter column activity_sequence set default nextval('writing_block_activity_sequence'),
  alter column activity_sequence set not null;

alter table writing_blocks
  add constraint writing_blocks_activity_sequence_key unique (activity_sequence);

alter sequence writing_block_activity_sequence
  owned by writing_blocks.activity_sequence;

alter table routines
  add column activity_cursor_sequence bigint,
  add column active_execution_id uuid,
  add column last_execution_id uuid;

update routines routine
set activity_cursor_sequence = (
  select max(block.activity_sequence)
  from writing_blocks block
  join writing_block_scopes membership
    on membership.workspace_id = block.workspace_id
   and membership.writing_block_id = block.id
   and membership.source_scope_id = routine.source_scope_id
   and membership.status = 'ACTIVE'
  where block.workspace_id = routine.workspace_id
    and block.status = 'ACTIVE'
    and block.ingested_at <= routine.last_run_at
)
where routine.last_run_at is not null;

create index writing_blocks_workspace_activity_idx
  on writing_blocks(workspace_id, activity_sequence)
  where status = 'ACTIVE';
