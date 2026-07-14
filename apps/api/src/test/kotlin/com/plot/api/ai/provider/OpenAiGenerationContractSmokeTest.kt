package com.plot.api.ai.provider

import com.openai.client.OpenAIClientImpl
import com.openai.core.ClientOptions
import com.plot.api.ai.prompt.ChangelogPromptFactory
import com.plot.api.config.PlotAiProperties
import com.plot.api.generation.ModelOutputValidator
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.SentenceOrigin
import com.plot.api.generation.model.SourceProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
import tools.jackson.databind.ObjectMapper

@EnabledIfEnvironmentVariable(named = "PLOT_AI_CONTRACT_SMOKE", matches = "(?i)true")
class OpenAiGenerationContractSmokeTest {
	private val mapper = ObjectMapper()
	private val validator = ModelOutputValidator()

	@Test
	fun `configured model satisfies writer reviewer and targeted rewrite contracts`() {
		val apiKey = requiredEnvironment("OPENAI_API_KEY")
		val modelName = requiredEnvironment("PLOT_AI_MODEL")
		val properties = PlotAiProperties(
			enabled = true,
			model = modelName,
			timeout = Duration.ofSeconds(environment("PLOT_AI_TIMEOUT_SECONDS")?.toLongOrNull() ?: 45),
			transportRetries = 0,
			schemaRetries = 0,
			maxOutputTokens = environment("PLOT_AI_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: 2_000,
		)
		val httpClient = SpringAiOpenAiHttpClient.builder().timeout(properties.timeout).build()
		val clientOptions = ClientOptions.builder()
			.httpClient(httpClient)
			.apiKey(apiKey)
			.baseUrl(environment("PLOT_AI_BASE_URL") ?: ClientOptions.PRODUCTION_URL)
			.maxRetries(0)
			.build()
		val openAiClient = OpenAIClientImpl(clientOptions)

		try {
			val chatModel = OpenAiChatModel.builder().openAiClient(openAiClient).build()
			val gateway = SpringAiOpenAiGenerationGateway(
				SpringAiStructuredChatTransport(ChatClient.builder(chatModel), properties),
				properties,
				ChangelogPromptFactory(mapper),
			)
			val corpus = requireNotNull(javaClass.getResource("/evals/generation-citation-cases.json"))
				.openStream()
				.use { mapper.readValue(it, EvalCorpus::class.java) }
			val accumulator = MetricsAccumulator(modelName)

			val writerCase = corpus.cases.first()
			val writerRunId = runId(writerCase.id, "writer")
			val writerEvidence = writerCase.toEvidence(writerRunId)
			val writerResult = gateway.write(WriterModelRequest(writerRunId, writerCase.instruction, writerEvidence))
			accumulator.record(writerResult.metadata)
			validator.assignSentenceIds(writerRunId, writerResult.value) { UUID.randomUUID() }

			corpus.cases.forEach { evalCase ->
				val runId = runId(evalCase.id, "reviewer")
				val evidence = evalCase.toEvidence(runId)
				val sentences = evalCase.toSentences(runId)
				val reviewResult = gateway.review(ReviewerModelRequest(runId, sentences, evidence))
				accumulator.record(reviewResult.metadata)
				val reviews = validator.validateReview(runId, sentences, evidence, reviewResult.value)
				accumulator.score(evalCase, reviews.associateBy { it.sentenceId })

				val targets = evalCase.sentences.filter { it.rewriteTarget }.map { UUID.fromString(it.id) }
				if (targets.isNotEmpty()) {
					val rewriteResult = gateway.rewrite(
						RewriteModelRequest(
							runId,
							sentences,
							targets,
							evidence,
							"Omit unsupported rollout scope and preserve only facts common to the selected evidence.",
						),
					)
					accumulator.record(rewriteResult.metadata)
					val rewritten = validator.applyTargetedRewrite(runId, sentences, targets, rewriteResult.value) { UUID.randomUUID() }
					assertEquals(targets.toSet(), rewritten.filter { it.origin == SentenceOrigin.REWRITTEN }.map { it.id }.toSet())
				}
			}

			val metrics = accumulator.finish(environment("PLOT_AI_COST_PER_1M_TOKENS_USD")?.toBigDecimalOrNull())
			println("generation-citation-contract ${mapper.writeValueAsString(metrics)}")
			assertTrue(metrics.unsupportedClaimRecall >= 1.0, "Unsupported-claim recall fell below the contract threshold")
			assertTrue(metrics.conflictRecall >= 1.0, "Conflict recall fell below the contract threshold")
			assertTrue(metrics.citationPrecision >= 0.8, "Citation precision fell below the contract threshold")
			assertEquals(0.0, metrics.notRequiredFalsePositiveRate, "The reviewer overused NOT_REQUIRED")
		} finally {
			openAiClient.close()
		}
	}

	private fun EvalCase.toEvidence(runId: UUID) = evidence.mapIndexed { index, item ->
		EvidenceSnapshot(
			id = UUID.fromString(item.id),
			generationRunId = runId,
			writingBlockId = UUID.nameUUIDFromBytes("$id-evidence-$index".toByteArray()),
			orderIndex = index,
			sourceProvider = SourceProvider.GITHUB,
			sourceKind = "eval_fixture",
			sourceLabel = item.label,
			snapshotTitle = item.title,
			snapshotBody = item.body,
			snapshotExcerpt = item.body.take(180),
			originalUrl = "https://github.com/plot-eval/fixtures/${item.id}",
			sourceCreatedAt = Instant.parse("2026-07-12T00:00:00Z"),
			sourceUpdatedAt = null,
			contentHash = "eval-${item.id}",
			capturedAt = Instant.parse("2026-07-14T00:00:00Z"),
		)
	}

	private fun EvalCase.toSentences(runId: UUID) = sentences.mapIndexed { index, item ->
		SentenceArtifact(
			id = UUID.fromString(item.id),
			generationRunId = runId,
			revisionId = UUID.nameUUIDFromBytes("$id-sentence-$index".toByteArray()),
			revisionNumber = 1,
			orderIndex = index,
			body = item.body,
			origin = SentenceOrigin.GENERATED,
		)
	}

	private fun requiredEnvironment(name: String): String = environment(name)
		?.takeIf { it.isNotBlank() }
		?: error("$name is required when PLOT_AI_CONTRACT_SMOKE=true")

	private fun environment(name: String): String? = System.getenv(name)?.trim()

	private fun runId(caseId: String, role: String): UUID = UUID.nameUUIDFromBytes("$caseId-$role".toByteArray())
}

internal data class EvalCorpus(val version: Int, val cases: List<EvalCase>)
internal data class EvalCase(
	val id: String,
	val tags: List<String>,
	val instruction: String,
	val evidence: List<EvalEvidence>,
	val sentences: List<EvalSentence>,
)
internal data class EvalEvidence(val id: String, val label: String, val title: String, val body: String)
internal data class EvalSentence(
	val id: String,
	val body: String,
	val expectedVerdict: ReviewVerdict,
	val expectedEvidenceIds: List<UUID>,
	val rewriteTarget: Boolean,
)

private class MetricsAccumulator(private val configuredModel: String) {
	private var calls = 0
	private var totalTokens = 0
	private var totalLatencyMillis = 0L
	private var actualModel: String? = null
	private var expectedUnsupported = 0
	private var foundUnsupported = 0
	private var expectedConflicts = 0
	private var foundConflicts = 0
	private var predictedCitations = 0
	private var correctCitations = 0
	private var factualSentences = 0
	private var notRequiredFalsePositives = 0

	fun record(metadata: ModelCallMetadata) {
		calls += 1
		totalTokens += metadata.totalTokens ?: 0
		totalLatencyMillis += metadata.latency.toMillis()
		actualModel = metadata.actualModel ?: actualModel
	}

	fun score(evalCase: EvalCase, reviews: Map<UUID, com.plot.api.generation.model.ValidatedSentenceReview>) {
		evalCase.sentences.forEach { sentence ->
			val review = reviews.getValue(UUID.fromString(sentence.id))
			if (sentence.expectedVerdict == ReviewVerdict.NEEDS_SUPPORT) {
				expectedUnsupported += 1
				if (review.verdict == ReviewVerdict.NEEDS_SUPPORT) foundUnsupported += 1
			}
			if (sentence.expectedVerdict == ReviewVerdict.CONFLICT) {
				expectedConflicts += 1
				if (review.verdict == ReviewVerdict.CONFLICT) foundConflicts += 1
			}
			if (review.verdict == ReviewVerdict.SUPPORTED) {
				predictedCitations += review.evidenceIds.size
				correctCitations += review.evidenceIds.count { it in sentence.expectedEvidenceIds }
			}
			if (sentence.expectedVerdict != ReviewVerdict.NOT_REQUIRED) {
				factualSentences += 1
				if (review.verdict == ReviewVerdict.NOT_REQUIRED) notRequiredFalsePositives += 1
			}
		}
	}

	fun finish(costPerMillionTokensUsd: BigDecimal?): ContractMetrics {
		val estimatedCost = costPerMillionTokensUsd?.multiply(BigDecimal(totalTokens))
			?.divide(BigDecimal(1_000_000), 6, RoundingMode.HALF_UP)
		return ContractMetrics(
			configuredModel = configuredModel,
			actualModel = actualModel,
			calls = calls,
			totalTokens = totalTokens,
			totalLatencyMillis = totalLatencyMillis,
			costPerMillionTokensUsd = costPerMillionTokensUsd,
			estimatedCostUsd = estimatedCost,
			unsupportedClaimRecall = ratio(foundUnsupported, expectedUnsupported),
			citationPrecision = ratio(correctCitations, predictedCitations),
			conflictRecall = ratio(foundConflicts, expectedConflicts),
			notRequiredFalsePositiveRate = ratio(notRequiredFalsePositives, factualSentences),
		)
	}

	private fun ratio(numerator: Int, denominator: Int): Double = if (denominator == 0) 1.0 else numerator.toDouble() / denominator
}

private data class ContractMetrics(
	val configuredModel: String,
	val actualModel: String?,
	val calls: Int,
	val totalTokens: Int,
	val totalLatencyMillis: Long,
	val costPerMillionTokensUsd: BigDecimal?,
	val estimatedCostUsd: BigDecimal?,
	val unsupportedClaimRecall: Double,
	val citationPrecision: Double,
	val conflictRecall: Double,
	val notRequiredFalsePositiveRate: Double,
)
