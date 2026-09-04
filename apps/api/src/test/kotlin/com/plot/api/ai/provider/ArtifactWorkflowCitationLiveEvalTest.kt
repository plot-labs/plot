package com.plot.api.ai.provider

import com.plot.api.TestcontainersConfiguration
import com.plot.api.artifact.workflow.ModelOutputValidator
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.artifact.workflow.model.SentenceOrigin
import com.plot.api.artifact.workflow.model.SourceProvider
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Tag("live-eval")
@EnabledIfEnvironmentVariable(named = "PLOT_EVAL_LIVE", matches = "true")
class ArtifactWorkflowCitationLiveEvalTest {

	@Autowired
	private lateinit var gateway: ArtifactWorkflowModelGateway

	@Autowired
	private lateinit var mapper: ObjectMapper

	private val validator = ModelOutputValidator()

	@Test
	fun `live eval scores model outputs against corpus expectations`() {
		val corpusResource = requireNotNull(javaClass.getResource("/evals/generation-citation-cases.json"))
		val corpusRoot = corpusResource.openStream().use(mapper::readTree)
		val cases = requireNotNull(corpusRoot["cases"]).asArray().values().toList()

		val failureMessages = mutableListOf<String>()
		val caseResults = mutableListOf<CaseResult>()

		cases.forEach { evalCase ->
			val caseId = evalCase["id"].stringValue()
			val runId = UUID.randomUUID()
			val instruction = evalCase["instruction"]?.stringValue()
			val evidence = parseEvidence(evalCase["evidence"], runId)
			val sentences = parseSentences(evalCase["sentences"], runId)

			try {
				val reviewResult = gateway.review(
					ReviewerModelRequest(
						artifactWorkflowRunId = runId,
						sentences = sentences,
						evidence = evidence,
					),
				)

				val validatedReviews = validator.validateReview(runId, sentences, evidence, reviewResult.value)

				val sentenceResults = mutableListOf<SentenceResult>()

				sentences.forEach { sentence ->
					val sentenceNode = evalCase["sentences"].asArray().values()
						.first { it["id"].stringValue() == sentence.id.toString() }
					val expectedVerdict = ReviewVerdict.valueOf(sentenceNode["expectedVerdict"].stringValue())
					val expectedEvidenceIds = sentenceNode["expectedEvidenceIds"].asArray().values()
						.map { UUID.fromString(it.stringValue()) }.toSet()

					val validated = validatedReviews.first { it.sentenceId == sentence.id }

					val verdictMatch = validated.verdict == expectedVerdict
					val evidenceMatch = validated.evidenceIds.toSet() == expectedEvidenceIds

					if (!verdictMatch) {
						failureMessages.add(
							"[$caseId] Sentence ${sentence.id}: verdict mismatch. " +
								"Expected: $expectedVerdict, Got: ${validated.verdict}",
						)
					}

					if (!evidenceMatch) {
						failureMessages.add(
							"[$caseId] Sentence ${sentence.id}: evidence IDs mismatch. " +
								"Expected: $expectedEvidenceIds, Got: ${validated.evidenceIds.toSet()}",
						)
					}

					sentenceResults.add(
						SentenceResult(
							sentenceId = sentence.id,
							verdictMatch = verdictMatch,
							evidenceMatch = evidenceMatch,
							actualVerdict = validated.verdict,
							actualEvidenceIds = validated.evidenceIds.toSet(),
						),
					)
				}

				val rewriteTarget = sentences.any { sentence ->
					val sentenceNode = evalCase["sentences"].asArray().values()
						.first { it["id"].stringValue() == sentence.id.toString() }
					sentenceNode["rewriteTarget"].booleanValue()
				}

				var rewriteSentenceResults: List<SentenceResult>? = null

				if (rewriteTarget) {
					val targetSentenceIds = sentences.filter { sentence ->
						val sentenceNode = evalCase["sentences"].asArray().values()
							.first { it["id"].stringValue() == sentence.id.toString() }
						sentenceNode["rewriteTarget"].booleanValue()
					}.map { it.id }

					val rewriteResult = gateway.rewrite(
						RewriteModelRequest(
							artifactWorkflowRunId = runId,
							sentences = sentences,
							targetSentenceIds = targetSentenceIds,
							evidence = evidence,
						),
					)

					val rewrittenSentences = validator.applyTargetedRewrite(
						runId = runId,
						current = sentences,
						targetSentenceIds = targetSentenceIds,
						output = rewriteResult.value,
						revisionIdGenerator = { UUID.randomUUID() },
					)

					val reReviewResult = gateway.review(
						ReviewerModelRequest(
							artifactWorkflowRunId = runId,
							sentences = rewrittenSentences,
							evidence = evidence,
						),
					)

					val reValidatedReviews = validator.validateReview(
						runId,
						rewrittenSentences,
						evidence,
						reReviewResult.value,
					)

					rewriteSentenceResults = targetSentenceIds.mapNotNull { sentenceId ->
						val sentenceNode = evalCase["sentences"].asArray().values()
							.first { it["id"].stringValue() == sentenceId.toString() }
						val rewriteExpectedVerdict = sentenceNode["rewriteExpectedVerdict"]?.let {
							if (!it.isNull) ReviewVerdict.valueOf(it.stringValue()) else null
						} ?: return@mapNotNull null

						val rewriteExpectedEvidenceIds = sentenceNode["rewriteExpectedEvidenceIds"]?.asArray()?.values()
							?.map { UUID.fromString(it.stringValue()) }?.toSet() ?: emptySet()

						val reValidated = reValidatedReviews.firstOrNull { it.sentenceId == sentenceId }

						if (reValidated == null) {
							failureMessages.add(
								"[$caseId] Sentence $sentenceId: omitted during rewrite but expected verdict $rewriteExpectedVerdict",
							)
							return@mapNotNull SentenceResult(
								sentenceId = sentenceId,
								verdictMatch = false,
								evidenceMatch = false,
								actualVerdict = null,
								actualEvidenceIds = emptySet(),
							)
						}

						val verdictMatch = reValidated.verdict == rewriteExpectedVerdict
						val evidenceMatch = reValidated.evidenceIds.toSet() == rewriteExpectedEvidenceIds

						if (!verdictMatch) {
							failureMessages.add(
								"[$caseId] Sentence $sentenceId after rewrite: verdict mismatch. " +
									"Expected: $rewriteExpectedVerdict, Got: ${reValidated.verdict}",
							)
						}

						if (!evidenceMatch) {
							failureMessages.add(
								"[$caseId] Sentence $sentenceId after rewrite: evidence IDs mismatch. " +
									"Expected: $rewriteExpectedEvidenceIds, Got: ${reValidated.evidenceIds.toSet()}",
							)
						}

						SentenceResult(
							sentenceId = sentenceId,
							verdictMatch = verdictMatch,
							evidenceMatch = evidenceMatch,
							actualVerdict = reValidated.verdict,
							actualEvidenceIds = reValidated.evidenceIds.toSet(),
						)
					}
				}

				caseResults.add(
					CaseResult(
						caseId = caseId,
						passed = sentenceResults.all { it.verdictMatch && it.evidenceMatch } &&
							(rewriteSentenceResults?.all { it.verdictMatch && it.evidenceMatch } ?: true),
						sentenceResults = sentenceResults,
						rewriteSentenceResults = rewriteSentenceResults,
					),
				)
			} catch (e: ArtifactWorkflowModelException) {
				failureMessages.add("[$caseId] Model call failed: ${e.code} - ${e.message}")
				caseResults.add(CaseResult(caseId = caseId, passed = false, sentenceResults = emptyList()))
			} catch (e: Exception) {
				failureMessages.add("[$caseId] Unexpected error: ${e.message}")
				caseResults.add(CaseResult(caseId = caseId, passed = false, sentenceResults = emptyList()))
			}
		}

		val passedCount = caseResults.count { it.passed }
		val totalCount = caseResults.size

		println("\n=== Live Citation Eval Results ===")
		println("Passed: $passedCount / $totalCount cases")
		caseResults.forEach { result ->
			val status = if (result.passed) "✓" else "✗"
			println("  $status ${result.caseId}")
		}
		println()

		if (failureMessages.isNotEmpty()) {
			val report = buildString {
				appendLine("Live citation eval failed with ${failureMessages.size} error(s):")
				failureMessages.forEach { appendLine("  - $it") }
			}
			error(report)
		}
	}

	private fun parseEvidence(evidenceNode: JsonNode, runId: UUID): List<EvidenceSnapshot> =
		evidenceNode.asArray().values().mapIndexed { index, node ->
			EvidenceSnapshot(
				id = UUID.fromString(node["id"].stringValue()),
				artifactWorkflowRunId = runId,
				writingBlockId = UUID.randomUUID(),
				orderIndex = index,
				sourceProvider = SourceProvider.GITHUB,
				sourceKind = "pull_request",
				sourceLabel = node["label"].stringValue(),
				snapshotTitle = node["title"].stringValue(),
				snapshotBody = node["body"].stringValue(),
				snapshotExcerpt = null,
				originalUrl = "https://example.test/${node["label"].stringValue()}",
				sourceCreatedAt = null,
				sourceUpdatedAt = null,
				contentHash = "eval-${node["id"].stringValue()}",
				capturedAt = Instant.now(),
			)
		}

	private fun parseSentences(sentencesNode: JsonNode, runId: UUID): List<SentenceArtifact> =
		sentencesNode.asArray().values().mapIndexed { index, node ->
			SentenceArtifact(
				id = UUID.fromString(node["id"].stringValue()),
				artifactWorkflowRunId = runId,
				revisionId = UUID.randomUUID(),
				revisionNumber = 1,
				orderIndex = index,
				body = node["body"].stringValue(),
				origin = SentenceOrigin.GENERATED,
			)
		}

	private data class SentenceResult(
		val sentenceId: UUID,
		val verdictMatch: Boolean,
		val evidenceMatch: Boolean,
		val actualVerdict: ReviewVerdict?,
		val actualEvidenceIds: Set<UUID>,
	)

	private data class CaseResult(
		val caseId: String,
		val passed: Boolean,
		val sentenceResults: List<SentenceResult>,
		val rewriteSentenceResults: List<SentenceResult>? = null,
	)
}
