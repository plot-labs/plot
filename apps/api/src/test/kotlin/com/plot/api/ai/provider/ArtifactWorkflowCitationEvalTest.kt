package com.plot.api.ai.provider

import com.plot.api.artifact.workflow.ModelOutputValidator
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.artifact.workflow.model.SentenceOrigin
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class ArtifactWorkflowCitationEvalTest {
	private val mapper = ObjectMapper()
	private val validator = ModelOutputValidator()

	@Test
	fun `deterministic fixture scorer validates all corpus cases against golden outputs`() {
		val corpusResource = requireNotNull(javaClass.getResource("/evals/generation-citation-cases.json"))
		val fixturesResource = requireNotNull(javaClass.getResource("/evals/generation-citation-cases-fixtures.json"))

		val corpusRoot = corpusResource.openStream().use(mapper::readTree)
		val fixturesRoot = fixturesResource.openStream().use(mapper::readTree)

		val cases = requireNotNull(corpusRoot["cases"]).asArray().values().toList()
		val fixtures = requireNotNull(fixturesRoot["fixtures"]).asArray().values()
			.associate { it["caseId"].stringValue() to it }

		val failureMessages = mutableListOf<String>()

		cases.forEach { evalCase ->
			val caseId = evalCase["id"].stringValue()
			val fixture = fixtures[caseId]
				?: error("Missing fixture for case: $caseId")

			val runId = UUID.randomUUID()
			val evidence = parseEvidence(evalCase["evidence"], runId)
			val sentences = parseSentences(evalCase["sentences"], runId)

			val reviewerOutputJson = requireNotNull(fixture["reviewerOutput"]) {
				"Missing reviewerOutput for case: $caseId"
			}
			val reviewerOutput = mapper.treeToValue(reviewerOutputJson, ReviewerOutput::class.java)

			try {
				val validatedReviews = validator.validateReview(runId, sentences, evidence, reviewerOutput)

				sentences.forEach { sentence ->
					val sentenceNode = evalCase["sentences"].asArray().values()
						.first { it["id"].stringValue() == sentence.id.toString() }
					val expectedVerdict = ReviewVerdict.valueOf(sentenceNode["expectedVerdict"].stringValue())
					val expectedEvidenceIds = sentenceNode["expectedEvidenceIds"].asArray().values()
						.map { UUID.fromString(it.stringValue()) }.toSet()

					val validated = validatedReviews.first { it.sentenceId == sentence.id }

					if (validated.verdict != expectedVerdict) {
						failureMessages.add(
							"[$caseId] Sentence ${sentence.id}: verdict mismatch. " +
								"Expected: $expectedVerdict, Got: ${validated.verdict}",
						)
					}

					if (validated.evidenceIds.toSet() != expectedEvidenceIds) {
						failureMessages.add(
							"[$caseId] Sentence ${sentence.id}: evidence IDs mismatch. " +
								"Expected: $expectedEvidenceIds, Got: ${validated.evidenceIds.toSet()}",
						)
					}
				}

				val rewriteTarget = sentences.any { sentence ->
					val sentenceNode = evalCase["sentences"].asArray().values()
						.first { it["id"].stringValue() == sentence.id.toString() }
					sentenceNode["rewriteTarget"].booleanValue()
				}

				if (rewriteTarget) {
					val rewriterOutputJson = fixture["rewriterOutput"]
					if (rewriterOutputJson == null || rewriterOutputJson.isNull) {
						failureMessages.add("[$caseId] Missing rewriterOutput for rewriteTarget case")
					} else {
						val targetSentenceIds = sentences.filter { sentence ->
							val sentenceNode = evalCase["sentences"].asArray().values()
								.first { it["id"].stringValue() == sentence.id.toString() }
							sentenceNode["rewriteTarget"].booleanValue()
						}.map { it.id }

						val rewriterOutput = mapper.treeToValue(rewriterOutputJson, TargetedRewriteOutput::class.java)
						val rewrittenSentences = validator.applyTargetedRewrite(
							runId = runId,
							current = sentences,
							targetSentenceIds = targetSentenceIds,
							output = rewriterOutput,
							revisionIdGenerator = { UUID.randomUUID() },
						)

						val rewriteAfterReviewerOutputJson = fixture["rewriteAfterReviewerOutput"]
						if (rewriteAfterReviewerOutputJson == null || rewriteAfterReviewerOutputJson.isNull) {
							failureMessages.add("[$caseId] Missing rewriteAfterReviewerOutput for rewriteTarget case")
						} else {
							val reReviewerOutput = mapper.treeToValue(
								rewriteAfterReviewerOutputJson,
								ReviewerOutput::class.java,
							)
							val reValidatedReviews = validator.validateReview(
								runId,
								rewrittenSentences,
								evidence,
								reReviewerOutput,
							)

							targetSentenceIds.forEach { sentenceId ->
								val sentenceNode = evalCase["sentences"].asArray().values()
									.first { it["id"].stringValue() == sentenceId.toString() }
								val rewriteExpectedVerdict = sentenceNode["rewriteExpectedVerdict"]?.let {
									if (!it.isNull) ReviewVerdict.valueOf(it.stringValue()) else null
								}
								val rewriteExpectedEvidenceIds = sentenceNode["rewriteExpectedEvidenceIds"]?.asArray()?.values()
									?.map { UUID.fromString(it.stringValue()) }?.toSet() ?: emptySet()

								if (rewriteExpectedVerdict != null) {
									val reValidated = reValidatedReviews.firstOrNull { it.sentenceId == sentenceId }
									if (reValidated == null) {
										failureMessages.add(
											"[$caseId] Sentence $sentenceId: omitted during rewrite but expected verdict $rewriteExpectedVerdict",
										)
									} else {
										if (reValidated.verdict != rewriteExpectedVerdict) {
											failureMessages.add(
												"[$caseId] Sentence $sentenceId after rewrite: verdict mismatch. " +
													"Expected: $rewriteExpectedVerdict, Got: ${reValidated.verdict}",
											)
										}

										if (reValidated.evidenceIds.toSet() != rewriteExpectedEvidenceIds) {
											failureMessages.add(
												"[$caseId] Sentence $sentenceId after rewrite: evidence IDs mismatch. " +
													"Expected: $rewriteExpectedEvidenceIds, Got: ${reValidated.evidenceIds.toSet()}",
											)
										}
									}
								}
							}
						}
					}
				}
			} catch (e: Exception) {
				failureMessages.add("[$caseId] Validation failed: ${e.message}")
			}
		}

		if (failureMessages.isNotEmpty()) {
			val report = buildString {
				appendLine("Citation eval fixture scorer failed with ${failureMessages.size} error(s):")
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
}
