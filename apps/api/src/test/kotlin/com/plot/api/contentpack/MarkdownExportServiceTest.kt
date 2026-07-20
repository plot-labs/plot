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
import kotlin.test.assertTrue

class MarkdownExportServiceTest {
	private val runId = UUID.fromString("00000000-0000-0000-0000-000000000001")
	private val github = evidence("00000000-0000-0000-0000-000000000021", "GitHub PR #42", "https://github.com/acme/app/pull/42", "private github excerpt")
	private val linear = evidence(
		"00000000-0000-0000-0000-000000000022",
		"Linear PLOT-9",
		"https://linear.app/acme/issue/PLOT-9",
		"private linear excerpt",
		SourceProvider.LINEAR,
	)

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
		assertTrue(acknowledged.markdown.contains("Unsupported claim."))
		assertTrue(acknowledged.markdown.contains("User wording."))
		assertFalse(acknowledged.markdown.contains("private github excerpt"))
	}

	@Test
	fun neutralizesActiveContentAndKeepsOnlyApprovedSourceLinksActive() {
		val hostileBody = """
			Release [click](javascript:alert(1)), ![pixel](https://attacker.example/pixel),
			<script>alert(1)</script>, <https://attacker.example/autolink>, https://attacker.example/bare,
			and data:text/html,boom.
		""".trimIndent().replace("\n", " ")
		val hostileEvidence = evidence(
			"00000000-0000-0000-0000-000000000023",
			"Hostile ](https://attacker.example)\n<script>label</script>",
			"javascript:alert(1)",
			"HIDDEN SNAPSHOT",
		)
		val result = MarkdownExportService().render(
			listOf(sentence(0, hostileBody, github.id, hostileEvidence.id)),
			listOf(github, hostileEvidence),
			false,
		)

		val activeDestinations = Regex("(?<!\\\\)\\]\\(([^)]+)\\)").findAll(result.markdown)
			.map { it.groupValues[1] }
			.toList()
		assertEquals(listOf(github.originalUrl), activeDestinations)
		assertFalse(result.markdown.contains("<script", ignoreCase = true))
		assertFalse(result.markdown.contains("!["))
		assertFalse(result.markdown.contains("<https://"))
		assertFalse(result.markdown.contains("https://attacker.example"))
		assertFalse(result.markdown.contains("javascript:", ignoreCase = true))
		assertFalse(result.markdown.contains("data:", ignoreCase = true))
		assertFalse(result.markdown.contains("HIDDEN SNAPSHOT"))
		assertTrue(result.markdown.contains("link unavailable"))
	}

	@Test
	fun allowsCanonicalHttpsLinksForEachSupportedProvider() {
		val slack = evidence(
			"00000000-0000-0000-0000-000000000024",
			"Slack release thread",
			"https://acme.slack.com/archives/C123/p456",
			"private slack excerpt",
			SourceProvider.SLACK,
		)
		val result = MarkdownExportService().render(
			listOf(sentence(0, "Release context.", github.id, slack.id, linear.id)),
			listOf(github, slack, linear),
			false,
		)

		assertTrue(result.markdown.contains("](https://github.com/acme/app/pull/42)"))
		assertTrue(result.markdown.contains("](https://acme.slack.com/archives/C123/p456)"))
		assertTrue(result.markdown.contains("](https://linear.app/acme/issue/PLOT-9)"))
		assertFalse(result.markdown.contains("private slack excerpt"))
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

	private fun evidence(
		id: String,
		label: String,
		url: String,
		excerpt: String,
		provider: SourceProvider = SourceProvider.GITHUB,
	) = EvidenceSnapshot(
		id = UUID.fromString(id),
		generationRunId = runId,
		writingBlockId = UUID.randomUUID(),
		orderIndex = 0,
		sourceProvider = provider,
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
