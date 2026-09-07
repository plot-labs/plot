-- R06: versioned workspace content profile + run freeze + USER_CONFIRMED evidence.

create table workspace_content_profile_revisions (
  id uuid primary key,
  workspace_id uuid not null,
  revision_no integer not null,
  product_summary text not null default '',
  primary_audience text not null default '',
  customer_terms text not null default '',
  tone text not null default '',
  default_locale text not null default 'en',
  banned_phrases jsonb not null default '[]'::jsonb,
  created_by_user_id uuid not null,
  created_at timestamptz not null,
  unique (workspace_id, id),
  unique (workspace_id, revision_no),
  foreign key (workspace_id) references workspaces(id) on delete restrict,
  foreign key (created_by_user_id) references users(id) on delete restrict,
  check (revision_no >= 1),
  check (length(trim(default_locale)) > 0)
);

create table workspace_content_profiles (
  workspace_id uuid primary key,
  current_revision_id uuid,
  updated_at timestamptz not null,
  foreign key (workspace_id) references workspaces(id) on delete restrict,
  foreign key (workspace_id, current_revision_id)
    references workspace_content_profile_revisions(workspace_id, id) on delete restrict
);

alter table agent_runs
  add column content_profile_revision_id uuid,
  add column content_brief_snapshot jsonb;

alter table agent_runs
  add constraint agent_runs_content_profile_revision_fk
  foreign key (workspace_id, content_profile_revision_id)
    references workspace_content_profile_revisions(workspace_id, id) on delete restrict;

-- Allow USER_CONFIRMED generation evidence without GitHub writing-block provenance.
alter table generation_inputs
  alter column writing_block_id drop not null,
  alter column original_url drop not null;

alter table generation_inputs
  drop constraint if exists generation_inputs_source_provider_check;

alter table generation_inputs
  drop constraint if exists generation_inputs_original_url_check;

alter table generation_inputs
  add constraint generation_inputs_source_provider_check
  check (source_provider in ('GITHUB', 'SLACK', 'LINEAR', 'USER_CONFIRMED'));

alter table generation_inputs
  add constraint generation_inputs_url_and_block_by_provider_check
  check (
    (
      source_provider = 'USER_CONFIRMED'
      and writing_block_id is null
      and original_url is null
      and source_scope_id is null
      and agent_run_input_id is null
    )
    or (
      source_provider <> 'USER_CONFIRMED'
      and writing_block_id is not null
      and original_url is not null
      and length(trim(original_url)) > 0
    )
  );

alter table generation_inputs
  drop constraint if exists generation_inputs_agent_provenance_check;

alter table generation_inputs
  add constraint generation_inputs_agent_provenance_check
  check (
    (
      source_provider = 'USER_CONFIRMED'
      and agent_run_input_id is null
      and source_scope_id is null
    )
    or (
      source_provider <> 'USER_CONFIRMED'
      and (
        (agent_run_id is null and agent_run_input_id is null)
        or (agent_run_id is not null and agent_run_input_id is not null and source_scope_id is not null)
      )
    )
  );
