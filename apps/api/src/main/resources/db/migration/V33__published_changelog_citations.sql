alter table workspaces
  add column public_citations_enabled boolean not null default true;

create table published_changelog_entry_sentences (
  id uuid primary key,
  workspace_id uuid not null,
  published_changelog_entry_id uuid not null,
  order_index integer not null,
  body text not null,
  unique (workspace_id, id),
  unique (workspace_id, published_changelog_entry_id, order_index),
  foreign key (workspace_id, published_changelog_entry_id)
    references published_changelog_entries(workspace_id, id) on delete restrict,
  check (order_index >= 0),
  check (length(trim(body)) > 0)
);

create table published_changelog_entry_citations (
  id uuid primary key,
  workspace_id uuid not null,
  published_changelog_entry_sentence_id uuid not null,
  citation_order integer not null,
  provider varchar not null,
  source_label text not null,
  original_url text not null,
  unique (workspace_id, id),
  unique (workspace_id, published_changelog_entry_sentence_id, citation_order),
  foreign key (workspace_id, published_changelog_entry_sentence_id)
    references published_changelog_entry_sentences(workspace_id, id) on delete restrict,
  check (citation_order >= 0),
  check (length(trim(provider)) > 0),
  check (length(trim(source_label)) > 0),
  check (length(trim(original_url)) > 0)
);

create index published_changelog_entry_sentences_entry_idx
  on published_changelog_entry_sentences(workspace_id, published_changelog_entry_id, order_index);

create index published_changelog_entry_citations_sentence_idx
  on published_changelog_entry_citations(
    workspace_id,
    published_changelog_entry_sentence_id,
    citation_order
  );
