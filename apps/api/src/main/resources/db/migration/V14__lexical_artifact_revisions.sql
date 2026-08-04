create table content_variant_revisions (
  id uuid primary key,
  workspace_id uuid not null,
  generation_run_id uuid not null,
  content_variant_id uuid not null,
  revision_no integer not null,
  lexical_content jsonb not null,
  is_current boolean not null default true,
  created_by_user_id uuid references users(id),
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, id, generation_run_id, content_variant_id),
  unique (workspace_id, content_variant_id, revision_no),
  foreign key (workspace_id, content_variant_id, generation_run_id)
    references content_variants(workspace_id, id, generation_run_id) on delete restrict,
  check (revision_no > 0),
  check (jsonb_typeof(lexical_content) = 'object')
);

create unique index content_variant_revisions_one_current_idx
  on content_variant_revisions(workspace_id, content_variant_id)
  where is_current;

create table content_variant_revision_sentences (
  id uuid primary key,
  workspace_id uuid not null,
  content_variant_revision_id uuid not null,
  generation_run_id uuid not null,
  content_variant_id uuid not null,
  sentence_id uuid not null,
  sentence_revision_id uuid not null,
  order_index integer not null,
  unique (workspace_id, id),
  unique (workspace_id, content_variant_revision_id, sentence_id),
  unique (workspace_id, content_variant_revision_id, order_index),
  foreign key (workspace_id, content_variant_revision_id, generation_run_id, content_variant_id)
    references content_variant_revisions(workspace_id, id, generation_run_id, content_variant_id) on delete restrict,
  foreign key (workspace_id, sentence_revision_id, generation_run_id, content_variant_id, sentence_id)
    references content_variant_sentence_revisions(workspace_id, id, generation_run_id, content_variant_id, sentence_id) on delete restrict,
  check (order_index >= 0)
);

-- Existing generated packs predate the artifact revision contract. Their first
-- revision is derived from the already materialized current sentence rows;
-- the variant id is a deterministic, workspace-local revision id for this
-- one-time backfill and is never reused for a later revision.
insert into content_variant_revisions (
  id, workspace_id, generation_run_id, content_variant_id, revision_no,
  lexical_content, is_current, created_at
)
select
  cv.id,
  cv.workspace_id,
  cv.generation_run_id,
  cv.id,
  1,
  jsonb_build_object(
    'root', jsonb_build_object(
      'children', coalesce(
        jsonb_agg(
          jsonb_build_object(
            'children', jsonb_build_array(
              jsonb_build_object(
                'detail', 0,
                'format', 0,
                'mode', 'normal',
                'style', '',
                'text', r.body,
                'type', 'text',
                'version', 1
              )
            ),
            'direction', null,
            'format', '',
            'indent', 0,
            'type', 'paragraph',
            'version', 1
          ) order by s.order_index
        ) filter (where s.id is not null),
        '[]'::jsonb
      ),
      'direction', null,
      'format', '',
      'indent', 0,
      'type', 'root',
      'version', 1
    )
  ),
  true,
  cv.created_at
from content_variants cv
left join content_variant_sentences s
  on s.workspace_id = cv.workspace_id
 and s.content_variant_id = cv.id
left join content_variant_sentence_revisions r
  on r.workspace_id = s.workspace_id
 and r.sentence_id = s.id
 and r.is_current
where not exists (
  select 1
  from content_variant_revisions existing
  where existing.workspace_id = cv.workspace_id
    and existing.content_variant_id = cv.id
)
group by cv.id, cv.workspace_id, cv.generation_run_id, cv.created_at;

insert into content_variant_revision_sentences (
  id, workspace_id, content_variant_revision_id, generation_run_id,
  content_variant_id, sentence_id, sentence_revision_id, order_index
)
select
  s.id,
  s.workspace_id,
  cv.id,
  cv.generation_run_id,
  cv.id,
  s.id,
  r.id,
  s.order_index
from content_variants cv
join content_variant_sentences s
  on s.workspace_id = cv.workspace_id
 and s.content_variant_id = cv.id
join content_variant_sentence_revisions r
  on r.workspace_id = s.workspace_id
 and r.sentence_id = s.id
 and r.is_current
where not exists (
  select 1
  from content_variant_revision_sentences existing
  where existing.workspace_id = s.workspace_id
    and existing.content_variant_revision_id = cv.id
    and existing.sentence_id = s.id
);

alter table generation_export_events
  add column artifact_revision_id uuid,
  add column artifact_revision_no integer,
  add column sentence_ids jsonb not null default '[]'::jsonb,
  add column acknowledged_warning_keys jsonb not null default '[]'::jsonb,
  add column include_sources boolean not null default false,
  add column renderer_version text not null default 'markdown-v2',
  add column export_input_hash text;

alter table sentence_citations
  add column stale_reason text;

alter table generation_export_events
  add constraint generation_export_events_artifact_revision_fk
  foreign key (workspace_id, artifact_revision_id, generation_run_id, content_variant_id)
  references content_variant_revisions(workspace_id, id, generation_run_id, content_variant_id)
  on delete restrict;

create function protect_content_variant_revision_history()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'DELETE' then
    raise exception '% is append-only', tg_table_name using errcode = '23514';
  end if;

  if new.id <> old.id
    or new.workspace_id <> old.workspace_id
    or new.generation_run_id <> old.generation_run_id
    or new.content_variant_id <> old.content_variant_id
    or new.revision_no <> old.revision_no
    or new.lexical_content <> old.lexical_content
    or new.created_by_user_id is distinct from old.created_by_user_id
    or new.created_at <> old.created_at
    or old.is_current = false
    or new.is_current = true then
    raise exception '% history is immutable', tg_table_name using errcode = '23514';
  end if;

  return new;
end;
$$;

create trigger content_variant_revisions_history
  before update or delete on content_variant_revisions
  for each row execute function protect_content_variant_revision_history();

create function reject_content_variant_revision_sentence_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = '23514';
end;
$$;

create trigger content_variant_revision_sentences_append_only
  before update or delete on content_variant_revision_sentences
  for each row execute function reject_content_variant_revision_sentence_mutation();
