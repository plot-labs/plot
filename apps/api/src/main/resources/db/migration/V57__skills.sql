create table skills (
    id uuid primary key,
    workspace_id uuid references workspaces(id),
    name varchar(64) not null,
    description varchar(500) not null,
    content text not null check (length(content) between 1 and 16000),
    revision integer not null default 1 check (revision > 0),
    is_system boolean not null default false,
    check ((is_system and workspace_id is null) or (not is_system and workspace_id is not null))
);
create unique index skills_system_name on skills(name) where is_system;
create unique index skills_workspace_name on skills(workspace_id, name) where not is_system;
alter table routines add column skills_snapshot jsonb not null default '[]'::jsonb check (jsonb_typeof(skills_snapshot) = 'array');
alter table agent_runs add column skills_snapshot jsonb not null default '[]'::jsonb check (jsonb_typeof(skills_snapshot) = 'array');
insert into skills (id, name, description, content, is_system) values
('019a0000-0000-7000-8000-000000000001', 'changelog', 'Turn product changes into a concise customer-facing changelog.', 'Group related changes by customer benefit. Explain what users can now do. Omit internal refactors unless they change user-visible behavior. Use only supplied evidence; never invent availability, pricing, metrics or links.', true),
('019a0000-0000-7000-8000-000000000002', 'launch-announcement', 'Explain a launch through its audience, benefit, and next action.', 'Lead with the customer problem and the capability now available. Use supported facts to explain who benefits and how. Include availability or a call to action only when confirmed by the supplied evidence or brief. Do not invent claims.', true),
('019a0000-0000-7000-8000-000000000003', 'humanizer', 'Make a draft sound natural, direct, and specific.', 'Use plain words, active voice and concrete benefits. Remove promotional filler, repetitive transitions and unsupported superlatives. Preserve every supported fact and its meaning. Never add new factual claims when improving style.', true);
