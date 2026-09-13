-- Drop legacy autonomy hierarchy in dependency order.
-- Retired tables: autonomy_executions, autonomy_tasks, autonomy_goals,
-- autonomy_assessments, autonomy_daily_budgets, autonomy_opportunities, autonomy_missions.

drop table if exists autonomy_executions;
drop table if exists autonomy_tasks;
drop table if exists autonomy_goals;
drop table if exists autonomy_assessments;
drop table if exists autonomy_daily_budgets;
drop table if exists autonomy_opportunities;
drop table if exists autonomy_missions;
