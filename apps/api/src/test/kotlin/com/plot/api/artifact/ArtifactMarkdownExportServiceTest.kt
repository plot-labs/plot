package com.plot.api.artifact

import com.plot.api.artifact.workflow.model.CitationStatus
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ExportSentence
import com.plot.api.artifact.workflow.model.ExportSentenceStatus
import com.plot.api.artifact.workflow.model.ExportSource
import com.plot.api.artifact.workflow.model.SentenceCitation
import com.plot.api.artifact.workflow.model.SourceProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class ArtifactMarkdownExportServiceTest {
	private val mapper = ObjectMapper()
	private val runId = UUID.fromString("00000000-0000-0000-0000-000000000001")
	private val github = evidence("00000000-0000-0000-0000-000000000021", "GitHub PR #42", "https://github.com/acme/app/pull/42", "private github excerpt")
	private val issue = evidence(
		"00000000-0000-0000-0000-000000000022",
		"GitHub issue #9",
		"https://github.com/acme/app/issues/9",
		"private issue excerpt",
	)

	@Test
	fun rendersOrderedBodiesAndOptInDeduplicatedSourcesWithoutInlineMarkers() {
		val first = sentence(0, "Search shipped.", github.id, issue.id)
		val second = sentence(1, "The editor shipped too.", github.id)

		val result = ArtifactMarkdownExportService().render(
			sentences = listOf(second, first),
			evidence = listOf(issue, github),
			acknowledgeUnresolved = false,
			includeSources = true,
			sources = listOf(
				ExportSource(github.id, "GITHUB", github.sourceLabel, github.originalUrl),
				ExportSource(issue.id, "GITHUB", issue.sourceLabel, issue.originalUrl),
				ExportSource(github.id, "GITHUB", github.sourceLabel, github.originalUrl),
			),
		)

		assertEquals(
			"Search shipped.\n\nThe editor shipped too.\n\n## Sources\n\n- [GitHub PR #42](https://github.com/acme/app/pull/42)\n- [GitHub issue #9](https://github.com/acme/app/issues/9)\n",
			result.markdown,
		)
		assertEquals(0, result.unresolvedCount)
		assertFalse(result.markdown.contains("[1]"))
		assertFalse(result.markdown.contains("private github excerpt"))
		assertFalse(result.markdown.contains("private issue excerpt"))
	}

	@Test
	fun excludesStaleCitationsAndUsesOnlyStoredCanonicalUrls() {
		val base = sentence(0, "Edited sentence.", github.id)
		val sentence = base.copy(
			status = ExportSentenceStatus.SUPPORTED,
			citations = listOf(
				SentenceCitation(base.id, base.revisionId, github.id, 0, CitationStatus.STALE),
				SentenceCitation(base.id, base.revisionId, issue.id, 1, CitationStatus.ACTIVE),
			),
		)

		val result = ArtifactMarkdownExportService().render(
			listOf(sentence),
			listOf(github, issue),
			acknowledgeUnresolved = false,
			includeSources = true,
			sources = listOf(ExportSource(issue.id, "GITHUB", issue.sourceLabel, issue.originalUrl)),
		)

		assertFalse(result.markdown.contains("https://github.com/acme/app/pull/42"))
		assertEquals(true, result.markdown.contains("https://github.com/acme/app/issues/9"))
		assertFalse(result.markdown.contains("attacker.example"))
		assertFalse(result.markdown.contains("private issue excerpt"))
	}

	@Test
	fun refusesUnresolvedExportUntilExplicitAcknowledgement() {
		val sentences = listOf(
			sentence(0, "Unsupported claim.", github.id).copy(status = ExportSentenceStatus.NEEDS_SUPPORT),
			sentence(1, "User wording.", github.id).copy(status = ExportSentenceStatus.USER_MODIFIED),
		)

		val error = assertFailsWith<UnresolvedExportException> {
			ArtifactMarkdownExportService().render(sentences, listOf(github), acknowledgeUnresolved = false, includeSources = false)
		}
		assertEquals(2, error.unresolvedCount)

		val acknowledged = ArtifactMarkdownExportService().render(sentences, listOf(github), acknowledgeUnresolved = true, includeSources = false)
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
		val result = ArtifactMarkdownExportService().render(
			listOf(sentence(0, hostileBody, github.id, hostileEvidence.id)),
			listOf(github, hostileEvidence),
			acknowledgeUnresolved = false,
			includeSources = true,
			sources = listOf(
				ExportSource(github.id, "GITHUB", github.sourceLabel, github.originalUrl),
				ExportSource(hostileEvidence.id, "GITHUB", hostileEvidence.sourceLabel, hostileEvidence.originalUrl),
			),
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
		assertFalse(result.markdown.contains("## Sources\n\n- [Hostile"))
	}

	@Test
	fun neutralizesMarkdownBlockSyntaxInUntrustedBodies() {
		val body = """
			# injected heading
			- injected list item
			1. injected ordered item
			> injected quote
			```injected code```
			| injected | table |
			    injected indented code
		""".trimIndent()

		val result = ArtifactMarkdownExportService().render(
			listOf(sentence(0, body, github.id)),
			listOf(github),
			acknowledgeUnresolved = false,
			includeSources = false,
		)

		assertFalse(Regex("(?m)^ {0,3}(#{1,6}\\s|>\\s?|[-+*]\\s|\\d+[.)]\\s|```|~~~)").containsMatchIn(result.markdown))
		assertFalse(Regex("(?m)^ {4}\\S").containsMatchIn(result.markdown))
		assertFalse(result.markdown.contains("| injected |"))
		assertTrue(result.markdown.contains("\\# injected heading"))
	}

	@Test
	fun appendsConfirmedFactsWithoutGithubLinks() {
		val confirmed = EvidenceSnapshot(
			id = UUID.fromString("00000000-0000-0000-0000-000000000024"),
			artifactWorkflowRunId = runId,
			writingBlockId = null,
			orderIndex = 1,
			sourceProvider = SourceProvider.USER_CONFIRMED,
			sourceKind = "AVAILABILITY",
			sourceLabel = "Confirmed availability",
			snapshotTitle = null,
			snapshotBody = "Generally available.",
			snapshotExcerpt = "Generally available.",
			originalUrl = null,
			sourceCreatedAt = null,
			sourceUpdatedAt = null,
			contentHash = "hash-confirmed",
			capturedAt = Instant.parse("2026-07-14T00:00:00Z"),
		)
		val result = ArtifactMarkdownExportService().render(
			sentences = listOf(sentence(0, "Search shipped.", github.id, confirmed.id)),
			evidence = listOf(github, confirmed),
			acknowledgeUnresolved = false,
			includeSources = true,
			sources = listOf(
				ExportSource(github.id, "GITHUB", github.sourceLabel, github.originalUrl),
				ExportSource(confirmed.id, "USER_CONFIRMED", confirmed.sourceLabel, null),
			),
		)

		assertTrue(result.markdown.contains("## Sources\n\n- [GitHub PR #42](https://github.com/acme/app/pull/42)"))
		assertTrue(result.markdown.contains("## Confirmed in Plot\n\n- Confirmed availability\n"))
		assertFalse(result.markdown.contains("](null)"))
		assertFalse(result.markdown.contains("Generally available."))
	}

	@Test
	fun rendersV2LayoutAndKeepsCtaIndependentFromSourceSection() {
		val heading = sentence(0, "Release notes", github.id)
		val item = sentence(1, "Search is faster.", github.id)
		val cta = sentence(2, "Join the beta", github.id)
		val document = mapper.readTree(
			"""
			{"documentVersion":2,"root":{"type":"root","version":1,"children":[
			{"type":"heading","nodeId":"00000000-0000-0000-0000-000000000101","statementId":"${heading.id}","tag":"h1","children":[]},
			{"type":"list","nodeId":"00000000-0000-0000-0000-000000000102","listType":"bullet","start":1,"children":[{"type":"listItem","nodeId":"00000000-0000-0000-0000-000000000103","statementId":"${item.id}","children":[]}]},
			{"type":"cta","nodeId":"00000000-0000-0000-0000-000000000104","statementId":"${cta.id}","destinationId":"00000000-0000-0000-0000-000000000105","destinationLabel":"Join the beta","destinationUrl":"https://plot.test/beta","children":[]}
			]}}
			""".trimIndent(),
		)

		val result = ArtifactMarkdownExportService().renderDocument(
			document = document,
			sentences = listOf(heading, item, cta),
			evidence = listOf(github),
			acknowledgeUnresolved = false,
			includeSources = true,
			sources = listOf(ExportSource(github.id, "GITHUB", github.sourceLabel, github.originalUrl)),
		)

		assertEquals(
			"# Release notes\n\n- Search is faster.\n\n[Join the beta](https://plot.test/beta)\n\n## Sources\n\n- [GitHub PR #42](https://github.com/acme/app/pull/42)\n",
			result.markdown,
		)
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
	) = EvidenceSnapshot(
		id = UUID.fromString(id),
		artifactWorkflowRunId = runId,
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
