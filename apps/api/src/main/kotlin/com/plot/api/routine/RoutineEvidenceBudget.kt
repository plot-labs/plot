package com.plot.api.routine

import com.plot.api.config.PlotAiProperties
import com.plot.api.writingblock.WritingBlock
import org.springframework.stereotype.Component

data class RoutineEvidenceBatch(
	val blocks: List<WritingBlock>,
	val consumedThrough: RoutineActivityCursor?,
	val oversizedBlockCount: Int,
)

@Component
class RoutineEvidenceBudget(
	properties: PlotAiProperties,
) {
	val maxBlocks: Int = MAX_BLOCKS
	val maxCharacters: Int = properties.maxEvidenceCharacters

	fun select(candidates: List<WritingBlock>): RoutineEvidenceBatch {
		val selected = mutableListOf<WritingBlock>()
		var consumedThrough: RoutineActivityCursor? = null
		var usedCharacters = 0
		for (candidate in candidates) {
			if (selected.size == maxBlocks) break
			val candidateCharacters = characters(candidate.title, candidate.body)
			if (candidateCharacters > maxCharacters) {
				if (selected.isNotEmpty()) break
				return RoutineEvidenceBatch(
					blocks = emptyList(),
					consumedThrough = candidate.activityCursor(),
					oversizedBlockCount = 1,
				)
			}
			if (usedCharacters + candidateCharacters > maxCharacters) break
			selected += candidate
			usedCharacters += candidateCharacters
			consumedThrough = candidate.activityCursor()
		}
		return RoutineEvidenceBatch(selected, consumedThrough, oversizedBlockCount = 0)
	}

	fun requireWithinBudget(blockCount: Int, characterCount: Int) {
		require(blockCount in 1..maxBlocks) {
			"Routine evidence must contain between 1 and $maxBlocks blocks"
		}
		require(characterCount <= maxCharacters) {
			"Routine evidence exceeds the $maxCharacters character limit"
		}
	}

	fun characters(title: String?, body: String?): Int = title.orEmpty().length + body.orEmpty().length

	private fun WritingBlock.activityCursor() = RoutineActivityCursor(activitySequence)

	private companion object {
		const val MAX_BLOCKS = 20
	}
}
