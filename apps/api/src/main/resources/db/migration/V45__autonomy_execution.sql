-- A logical autonomy task delegates its drafting/review steps and physical retries to AgentRun.
create table autonomy_tasks (
 id uuid primary key, workspace_id uuid not null, goal_id uuid not null,
 action varchar not null check (action in ('PREPARE_REVIEWABLE_DRAFT')),
 task_version integer not null default 1 check (task_version between 1 and 3),
 state varchar not null check (state in ('RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 unique (workspace_id,id), unique (workspace_id,goal_id,task_version,action),
 foreign key (workspace_id,goal_id) references autonomy_goals(workspace_id,id)
);
create table autonomy_executions (
 id uuid primary key, workspace_id uuid not null, task_id uuid not null, agent_run_id uuid not null,
 state varchar not null check (state in ('RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 outcome varchar check (outcome in ('DRAFT_READY','NEEDS_REVIEW','FAILED','CANCELLED')),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 unique (workspace_id,id), unique (workspace_id,task_id), unique (workspace_id,agent_run_id),
 foreign key (workspace_id,task_id) references autonomy_tasks(workspace_id,id),
 foreign key (workspace_id,agent_run_id) references agent_runs(workspace_id,id),
 check ((state='RUNNING') = (outcome is null)),
 check ((state='RUNNING' and outcome is null) or
        (state='SUCCEEDED' and outcome in ('DRAFT_READY','NEEDS_REVIEW')) or
        (state='FAILED' and outcome='FAILED') or (state='CANCELLED' and outcome='CANCELLED'))
);
create index autonomy_executions_running_idx on autonomy_executions(workspace_id) where state='RUNNING';
