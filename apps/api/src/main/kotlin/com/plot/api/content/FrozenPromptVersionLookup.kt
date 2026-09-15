package com.plot.api.content

import com.plot.api.persistence.SqlExecutor
import java.util.UUID
import org.springframework.stereotype.Component

fun interface FrozenPromptVersionLookup {
	fun promptVersionFor(artifactWorkflowRunId: UUID): String
}

@Component
class GenerationRunFrozenPromptVersionLookup(
	private val sqlExecutor: SqlExecutor,
) : FrozenPromptVersionLookup {
	override fun promptVersionFor(artifactWorkflowRunId: UUID): String =
		sqlExecutor.query(
			"select prompt_version from generation_runs where id = ?",
			{ rs, _ -> requireNotNull(rs.getString(1)) },
			artifactWorkflowRunId,
		).firstOrNull() ?: ContentTypeRegistry.CHANGELOG_PROMPT_VERSION
}
