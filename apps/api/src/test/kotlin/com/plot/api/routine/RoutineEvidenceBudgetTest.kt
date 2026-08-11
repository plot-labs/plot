package com.plot.api.routine

import com.plot.api.config.PlotAiProperties
import com.plot.api.writingblock.WritingBlock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineEvidenceBudgetTest {
	@Test
	fun `count budget consumes only the first twenty activities`() {
		val budget = RoutineEvidenceBudget(PlotAiProperties(maxEvidenceCharacters = 1_000))
		val candidates = (1..21).map { block(it, title = "item-$it") }

		val batch = budget.select(candidates)

		assertEquals(candidates.take(20).map { it.id }, batch.blocks.map { it.id })
		assertEquals(candidates[19].activitySequence, batch.consumedThrough?.sequence)
	}

	@Test
	fun `oversized head is an explicit terminal skip and does not consume later evidence`() {
		val budget = RoutineEvidenceBudget(PlotAiProperties(maxEvidenceCharacters = 10))
		val before = block(1, title = "small")
		val oversized = block(2, title = "x".repeat(11))
		val after = block(3, title = "later")

		val prefix = budget.select(listOf(before, oversized, after))
		assertEquals(listOf(before.id), prefix.blocks.map { it.id })
		assertEquals(before.activitySequence, prefix.consumedThrough?.sequence)
		assertEquals(0, prefix.oversizedBlockCount)

		val terminalSkip = budget.select(listOf(oversized, after))
		assertTrue(terminalSkip.blocks.isEmpty())
		assertEquals(oversized.activitySequence, terminalSkip.consumedThrough?.sequence)
		assertEquals(1, terminalSkip.oversizedBlockCount)

		val next = budget.select(listOf(after))
		assertEquals(listOf(after.id), next.blocks.map { it.id })
	}

	private fun block(index: Int, title: String): WritingBlock {
		val id = UUID.fromString("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
		val at = Instant.parse("2026-08-09T00:00:00Z")
		return WritingBlock(
			id = id,
			workspaceId = UUID.randomUUID(),
			sourceNamespaceId = UUID.randomUUID(),
			externalObjectKey = "commit:$id",
			sourceOrigin = "integration",
			sourceKind = "commit",
			title = title,
			body = null,
			url = "https://github.com/acme/plot/commit/$id",
			canonicalUrl = "https://github.com/acme/plot/commit/$id",
			author = null,
			platform = "github",
			metadata = emptyMap(),
			contentHash = null,
			sourceCreatedAt = at,
			sourceUpdatedAt = at,
			ingestedAt = at,
			status = "ACTIVE",
			createdByUserId = null,
			createdAt = at,
			updatedAt = at,
			activitySequence = index.toLong(),
		)
	}
}
