ALTER TABLE agent_runs ADD COLUMN skill_catalog jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(skill_catalog) = 'array');
UPDATE agent_runs SET skill_catalog = skills_snapshot;
