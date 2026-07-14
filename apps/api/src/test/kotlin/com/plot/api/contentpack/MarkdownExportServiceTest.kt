package com.plot.api.contentpack

import com.plot.api.generation.model.CitationStatus
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ExportSentence
import com.plot.api.generation.model.ExportSentenceStatus
import com.plot.api.generation.model.SentenceCitation
import com.plot.api.generation.model.SourceProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MarkdownExportServiceTest {
	private val runId = UUID.fromString("00000000-0000-0000-0000-000000000001")
	private val github = evidence("00000000-0000-0000-0000-000000000021", "GitHub PR #42", "https://github.com/acme/app/pull/42", "private github excerpt")
	private val linear = evidence("00000000-0000-0000-0000-000000000022", "Linear PLOT-9", "https://linear.app/acme/issue/PLOT-9", "private linear excerpt")

	@Test
	fun rendersDeterministicNumericCitationsAndDeduplicatedSources() {
		val first = sentence(0, "Search shipped.", github.id, linear.id)
		val second = sentence(1, "The editor shipped too.", github.id)

		val result = MarkdownExportService().render(
			sentences = listOf(second, first),
			evidence = listOf(linear, github),
			acknowledgeUnresolved = false,
		)

		assertEquals(
			"Search shipped. [1][2]\n\nThe editor shipped too. [1]\n\n## Sources\n\n1. [GitHub PR #42](https://github.com/acme/app/pull/42)\n2. [Linear PLOT-9](https://linear.app/acme/issue/PLOT-9)\n",
			result.markdown,
		)
		assertEquals(0, result.unresolvedCount)
		assertFalse(result.markdown.contains("private github excerpt"))
		assertFalse(result.markdown.contains("private linear excerpt"))
	}

	@Test
	fun excludesStaleCitationsAndUsesOnlyStoredCanonicalUrls() {
		val base = sentence(0, "Edited sentence.", github.id)
		val sentence = base.copy(
			status = ExportSentenceStatus.SUPPORTED,
			citations = listOf(
				SentenceCitation(base.id, base.revisionId, github.id, 0, CitationStatus.STALE),
				SentenceCitation(base.id, base.revisionId, linear.id, 1, CitationStatus.ACTIVE),
			),
		)

		val result = MarkdownExportService().render(listOf(sentence), listOf(github, linear), false)

		assertFalse(result.markdown.contains("github.com"))
		assertEquals(true, result.markdown.contains("https://linear.app/acme/issue/PLOT-9"))
		assertFalse(result.markdown.contains("attacker.example"))
		assertFalse(result.markdown.contains("private linear excerpt"))
	}

	@Test
	fun refusesUnresolvedExportUntilExplicitAcknowledgement() {
		val sentences = listOf(
			sentence(0, "Unsupported claim.", github.id).copy(status = ExportSentenceStatus.NEEDS_SUPPORT),
			sentence(1, "User wording.", github.id).copy(status = ExportSentenceStatus.USER_MODIFIED),
		)

		val error = assertFailsWith<UnresolvedExportException> {
			MarkdownExportService().render(sentences, listOf(github), acknowledgeUnresolved = false)
		}
		assertEquals(2, error.unresolvedCount)

		val acknowledged = MarkdownExportService().render(sentences, listOf(github), acknowledgeUnresolved = true)
		assertEquals(2, acknowledged.unresolvedCount)
		assertEquals(true, acknowledged.warningAcknowledged)
		assertFalse(acknowledged.markdown.contains("private github excerpt"))
	}

	private fun sentence(orderIndex: Int, body: String, vararg evidenceIds: UUID): ExportSentence {
		val sentenceId = UUID.randomUUID()
		val revisionId = UUID.randomUUID()
		return ExportSentence(
			id = sentenceId,
			revisionId = revisionId,
			orderIndex = orderIndex,
			body = body,
			status = ExportSentenceStatus.SUPPORTED,
			citations = evidenceIds.mapIndexed { index, evidenceId ->
				SentenceCitation(sentenceId, revisionId, evidenceId, index)
			},
		)
	}

	private fun evidence(id: String, label: String, url: String, excerpt: String) = EvidenceSnapshot(
		id = UUID.fromString(id),
		generationRunId = runId,
		writingBlockId = UUID.randomUUID(),
		orderIndex = 0,
		sourceProvider = SourceProvider.GITHUB,
		sourceKind = "pull_request",
		sourceLabel = label,
		snapshotTitle = label,
		snapshotBody = excerpt,
		snapshotExcerpt = excerpt,
		originalUrl = url,
		sourceCreatedAt = null,
		sourceUpdatedAt = null,
		contentHash = "hash-$id",
		capturedAt = Instant.parse("2026-07-14T00:00:00Z"),
	)
}
