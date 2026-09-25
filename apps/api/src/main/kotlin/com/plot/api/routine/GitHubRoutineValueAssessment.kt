package com.plot.api.routine

import com.plot.api.writingblock.WritingBlock

/** A conservative first gate for change events until a richer assessment is available. */
internal object GitHubRoutineValueAssessment {
	enum class Decision { ELIGIBLE, EXCLUDED, AWAITING_EVIDENCE }

	private val customerChange = Regex("^(feat|fix|perf|security|revert)(\\([^)]{1,80}\\))?!?:", RegexOption.IGNORE_CASE)
	private val maintenanceChange = Regex("^(chore|ci|test|build|style|refactor)(\\([^)]{1,80}\\))?!?:", RegexOption.IGNORE_CASE)

	fun assess(blocks: List<WritingBlock>): Decision {
		if (blocks.isEmpty()) return Decision.EXCLUDED
		val titles = blocks.map { it.title.orEmpty().trim() }
		if (titles.any(customerChange::containsMatchIn)) return Decision.ELIGIBLE
		if (titles.all(maintenanceChange::containsMatchIn)) return Decision.EXCLUDED
		return Decision.AWAITING_EVIDENCE
	}
}
