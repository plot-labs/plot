package com.plot.api.generation

import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.SentenceOrigin
import com.plot.api.generation.model.SentenceReview
import com.plot.api.generation.model.SourceProvider
import com.plot.api.generation.model.TargetedRewrite
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import com.plot.api.generation.model.WriterSentence
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ModelOutputValidatorTest {
	private val runId = UUID.fromString("00000000-0000-0000-0000-000000000001")
	private val sentenceOne = sentence("00000000-0000-0000-0000-000000000011", 0, "Shipped search.")
	private val sentenceTwo = sentence("00000000-0000-0000-0000-000000000012", 1, "Thanks for reading.")
	private val github = evidence("00000000-0000-0000-0000-000000000021", SourceProvider.GITHUB)
	private val slack = evidence("00000000-0000-0000-0000-000000000022", SourceProvider.SLACK)

	@Test
	fun assignsStableBackendSentenceIdsToOrderedWriterOutput() {
		val ids = ArrayDeque(listOf(sentenceOne.id, UUID.randomUUID(), sentenceTwo.id, UUID.randomUUID()))

		val result = ModelOutputValidator().assignSentenceIds(
			runId,
			WriterOutput(listOf(WriterSentence("Shipped search."), WriterSentence("Thanks for reading."))),
		) { ids.removeFirst() }

		assertEquals(listOf(sentenceOne.id, sentenceTwo.id), result.map { it.id })
		assertEquals(listOf(0, 1), result.map { it.orderIndex })
		assertEquals(listOf("Shipped search.", "Thanks for reading."), result.map { it.body })
	}

	@Test
	fun requiresExactlyOneReviewForEverySentence() {
		val validator = ModelOutputValidator()
		val missing = ReviewerOutput(listOf(SentenceReview(sentenceOne.id, ReviewVerdict.SUPPORTED, listOf(github.id))))
		val duplicate = ReviewerOutput(listOf(
			SentenceReview(sentenceOne.id, ReviewVerdict.SUPPORTED, listOf(github.id)),
			SentenceReview(sentenceOne.id, ReviewVerdict.SUPPORTED, listOf(github.id)),
			SentenceReview(sentenceTwo.id, ReviewVerdict.NOT_REQUIRED),
		))

		assertFailsWith<InvalidModelOutputException> {
			validator.validateReview(runId, listOf(sentenceOne, sentenceTwo), listOf(github), missing)
		}
		assertFailsWith<InvalidModelOutputException> {
			validator.validateReview(runId, listOf(sentenceOne, sentenceTwo), listOf(github), duplicate)
		}
	}

	@Test
	fun validatesSupportedAndNotRequiredEvidenceRulesAndPreservesMultipleCitations() {
		val output = ReviewerOutput(listOf(
			SentenceReview(
				sentenceOne.id,
				ReviewVerdict.SUPPORTED,
				listOf(github.id, slack.id),
				modelSuppliedUrls = listOf("https://attacker.example/fake-source"),
			),
			SentenceReview(sentenceTwo.id, ReviewVerdict.NOT_REQUIRED),
		))

		val result = ModelOutputValidator().validateReview(
			runId,
			listOf(sentenceOne, sentenceTwo),
			listOf(github, slack),
			output,
		)

		assertEquals(listOf(github.id, slack.id), result.first().evidenceIds)
		assertFalse(result.first().toString().contains("attacker.example"))
		assertEquals(ReviewVerdict.NOT_REQUIRED, result.last().verdict)
		assertFailsWith<InvalidModelOutputException> {
			ModelOutputValidator().validateReview(
				runId,
				listOf(sentenceOne),
				listOf(github),
				ReviewerOutput(listOf(SentenceReview(sentenceOne.id, ReviewVerdict.SUPPORTED))),
			)
		}
		assertFailsWith<InvalidModelOutputException> {
			ModelOutputValidator().validateReview(
				runId,
				listOf(sentenceTwo),
				listOf(github),
				ReviewerOutput(listOf(SentenceReview(sentenceTwo.id, ReviewVerdict.NOT_REQUIRED, listOf(github.id)))),
			)
		}
	}

	@Test
	fun rejectsUnknownCrossRunAndDuplicateEvidenceReferences() {
		val otherRunEvidence = github.copy(generationRunId = UUID.randomUUID())
		val unknown = UUID.randomUUID()
		val validator = ModelOutputValidator()

		for (evidenceIds in listOf(listOf(unknown), listOf(github.id, github.id))) {
			assertFailsWith<InvalidModelOutputException> {
				validator.validateReview(
					runId,
					listOf(sentenceOne),
					listOf(github),
					ReviewerOutput(listOf(SentenceReview(sentenceOne.id, ReviewVerdict.SUPPORTED, evidenceIds))),
				)
			}
		}
		assertFailsWith<InvalidModelOutputException> {
			validator.validateReview(
				runId,
				listOf(sentenceOne),
				listOf(otherRunEvidence),
				ReviewerOutput(listOf(SentenceReview(sentenceOne.id, ReviewVerdict.SUPPORTED, listOf(github.id)))),
			)
		}
	}

	@Test
	fun targetedRewriteChangesExactTargetsAndPreservesNonTargetsByIdentity() {
		val newRevisionId = UUID.fromString("00000000-0000-0000-0000-000000000099")

		val result = ModelOutputValidator().applyTargetedRewrite(
			runId = runId,
			current = listOf(sentenceOne, sentenceTwo),
			targetSentenceIds = listOf(sentenceOne.id),
			output = TargetedRewriteOutput(listOf(TargetedRewrite(sentenceOne.id, "Search is now available."))),
		) { newRevisionId }

		assertEquals("Search is now available.", result.first().body)
		assertEquals(2, result.first().revisionNumber)
		assertEquals(SentenceOrigin.REWRITTEN, result.first().origin)
		assertSame(sentenceTwo, result.last())
	}

	@Test
	fun targetedRewriteRejectsMissingExtraOrReorderedTargets() {
		val validator = ModelOutputValidator()
		val targets = listOf(sentenceOne.id, sentenceTwo.id)
		val invalid = listOf(
			TargetedRewriteOutput(listOf(TargetedRewrite(sentenceOne.id, "Only one"))),
			TargetedRewriteOutput(listOf(
				TargetedRewrite(sentenceOne.id, "One"),
				TargetedRewrite(sentenceTwo.id, "Two"),
				TargetedRewrite(UUID.randomUUID(), "Extra"),
			)),
			TargetedRewriteOutput(listOf(
				TargetedRewrite(sentenceTwo.id, "Two"),
				TargetedRewrite(sentenceOne.id, "One"),
			)),
		)

		invalid.forEach { output ->
			assertFailsWith<InvalidModelOutputException> {
				validator.applyTargetedRewrite(runId, listOf(sentenceOne, sentenceTwo), targets, output) { UUID.randomUUID() }
			}
		}
	}

	private fun sentence(id: String, orderIndex: Int, body: String) = SentenceArtifact(
		id = UUID.fromString(id),
		generationRunId = runId,
		revisionId = UUID.randomUUID(),
		revisionNumber = 1,
		orderIndex = orderIndex,
		body = body,
		origin = SentenceOrigin.GENERATED,
	)

	private fun evidence(id: String, provider: SourceProvider) = EvidenceSnapshot(
		id = UUID.fromString(id),
		generationRunId = runId,
		writingBlockId = UUID.randomUUID(),
		orderIndex = 0,
		sourceProvider = provider,
		sourceKind = "message",
		sourceLabel = provider.name,
		snapshotTitle = provider.name,
		snapshotBody = "Evidence",
		snapshotExcerpt = "Evidence",
		originalUrl = "https://example.com/source",
		sourceCreatedAt = null,
		sourceUpdatedAt = null,
		contentHash = "hash",
		capturedAt = Instant.parse("2026-07-14T00:00:00Z"),
	)
}
