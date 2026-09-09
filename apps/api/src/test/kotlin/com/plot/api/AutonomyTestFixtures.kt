package com.plot.api

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate

/** Remove always-on runtime records before deleting their workspace source fixtures. */
fun clearAutonomyFixtures(jdbc: JdbcTemplate, workspaceId: UUID) {
    listOf("autonomy_executions", "autonomy_tasks", "autonomy_goals", "autonomy_assessments",
        "autonomy_opportunities", "autonomy_missions", "autonomy_daily_budgets", "autonomy_signal_heads", "autonomy_signals").forEach {
        jdbc.update("delete from $it where workspace_id = ?", workspaceId)
    }
}
