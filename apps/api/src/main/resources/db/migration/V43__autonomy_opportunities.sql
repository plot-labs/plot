create table autonomy_missions (
 id uuid primary key, workspace_id uuid not null references workspaces(id), source_scope_id uuid not null,
 state varchar not null default 'ACTIVE' check (state in ('ACTIVE','PAUSED')),
 created_at timestamptz not null, updated_at timestamptz not null,
 unique (workspace_id,source_scope_id), unique (workspace_id,id),
 foreign key (workspace_id,source_scope_id) references source_scopes(workspace_id,id)
);
create table autonomy_opportunities (
 id uuid primary key, workspace_id uuid not null references workspaces(id), source_scope_id uuid not null,
 subject_key text not null check (length(subject_key) between 1 and 500), title text not null,
 fingerprint varchar(64), disposition varchar not null default 'AWAITING_EVIDENCE'
 check (disposition in ('EXCLUDED','ACCUMULATING','AWAITING_EVIDENCE','ELIGIBLE')),
 reason text not null default 'Assessment pending', input_snapshot jsonb,
 evidence_ids jsonb not null default '[]', missing_facts jsonb not null default '[]',
 last_error_code varchar(100), dismissed boolean not null default false, version bigint not null default 0,
 created_at timestamptz not null, updated_at timestamptz not null,
 unique (workspace_id,source_scope_id,subject_key), unique (workspace_id,id),
 foreign key (workspace_id,source_scope_id) references source_scopes(workspace_id,id)
);
create table autonomy_assessments (
 workspace_id uuid not null, opportunity_id uuid not null, fingerprint varchar(64) not null,
 status varchar not null check (status in ('PROCESSING','SUCCEEDED','FAILED')),
 claim_token uuid not null, lease_until timestamptz not null, attempts integer not null default 1,
 result jsonb, error_code varchar(100), recoverable boolean not null default false,
 primary key (workspace_id,opportunity_id,fingerprint),
 foreign key (workspace_id,opportunity_id) references autonomy_opportunities(workspace_id,id)
);
create table autonomy_daily_budgets (
 workspace_id uuid not null references workspaces(id), budget_date date not null,
 assessment_count integer not null default 0 check (assessment_count >= 0),
 primary key (workspace_id,budget_date)
);
create table autonomy_goals (
 id uuid primary key, workspace_id uuid not null, opportunity_id uuid not null,
 fingerprint varchar(64) not null, input_snapshot jsonb not null,
 state varchar not null default 'QUEUED' check (state in ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 agent_run_id uuid, created_at timestamptz not null, updated_at timestamptz not null,
 unique (workspace_id,id), unique (workspace_id,opportunity_id,fingerprint), unique (workspace_id,agent_run_id),
 foreign key (workspace_id,opportunity_id) references autonomy_opportunities(workspace_id,id),
 foreign key (workspace_id,agent_run_id) references agent_runs(workspace_id,id)
);
create index autonomy_opportunities_home_idx on autonomy_opportunities(workspace_id,updated_at desc,id);

create unique index autonomy_goals_one_active_idx on autonomy_goals(workspace_id,opportunity_id) where state in ('QUEUED','RUNNING');
