-- V19 advanced last_run_at after one bounded batch. Anchor upgraded routines to
-- the evidence that was actually consumed so any remaining backlog stays visible.
update routines routine
set activity_cursor_sequence = (
  select max(block.activity_sequence)
  from generation_inputs input
  join generation_runs run
    on run.workspace_id = input.workspace_id
   and run.id = input.generation_run_id
  join writing_blocks block
    on block.workspace_id = input.workspace_id
   and block.id = input.writing_block_id
  where input.workspace_id = routine.workspace_id
    and input.generation_run_id = routine.last_generation_run_id
    and run.source_scope_id = routine.source_scope_id
)
where routine.last_run_at is not null;

create function sequence_writing_block_activity()
returns trigger
language plpgsql
as $$
begin
  perform pg_advisory_xact_lock(
    hashtextextended('routine-activity:' || new.workspace_id::text, 0)
  );

  if tg_op = 'UPDATE' and row(
    new.source_origin, new.title, new.body, new.url, new.canonical_url,
    new.author, new.platform, new.metadata, new.content_hash,
    new.source_created_at, new.source_updated_at, new.status
  ) is distinct from row(
    old.source_origin, old.title, old.body, old.url, old.canonical_url,
    old.author, old.platform, old.metadata, old.content_hash,
    old.source_created_at, old.source_updated_at, old.status
  ) then
    new.activity_sequence := nextval('writing_block_activity_sequence');
  end if;

  return new;
end;
$$;

create trigger writing_blocks_activity_sequence_trigger
before insert or update of
  source_origin, title, body, url, canonical_url, author, platform, metadata,
  content_hash, source_created_at, source_updated_at, status
on writing_blocks
for each row execute function sequence_writing_block_activity();

create function sequence_writing_block_membership_activity()
returns trigger
language plpgsql
as $$
declare
  became_visible boolean;
begin
  if tg_op = 'INSERT' then
    became_visible := new.status = 'ACTIVE';
  else
    became_visible := new.status = 'ACTIVE' and (
      old.status is distinct from new.status
      or old.source_scope_id is distinct from new.source_scope_id
      or old.writing_block_id is distinct from new.writing_block_id
    );
  end if;

  if became_visible then
    perform pg_advisory_xact_lock(
      hashtextextended('routine-activity:' || new.workspace_id::text, 0)
    );
    update writing_blocks
    set activity_sequence = nextval('writing_block_activity_sequence')
    where workspace_id = new.workspace_id and id = new.writing_block_id;
  end if;

  return new;
end;
$$;

create trigger writing_block_scopes_activity_sequence_trigger
after insert or update of status, source_scope_id, writing_block_id
on writing_block_scopes
for each row execute function sequence_writing_block_membership_activity();
