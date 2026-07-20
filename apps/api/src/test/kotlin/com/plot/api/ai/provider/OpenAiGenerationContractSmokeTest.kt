package com.plot.api.ai.provider

import com.openai.client.OpenAIClientImpl
import com.openai.client.OpenAIClientAsyncImpl
import com.openai.core.ClientOptions
import com.plot.api.ai.prompt.ChangelogPromptFactory
import com.plot.api.certification.CampaignManifest
import com.plot.api.certification.CanonicalExternalOriginPolicy
import com.plot.api.certification.CertificationArtifactContract
import com.plot.api.certification.CertificationAttempt
import com.plot.api.certification.CertificationAttemptMatrix
import com.plot.api.certification.CertificationCaseEvaluator
import com.plot.api.certification.CertificationCaseObservation
import com.plot.api.certification.CertificationFailureCode
import com.plot.api.certification.CertificationGenerationProfileContract
import com.plot.api.certification.CertificationProfileHashes
import com.plot.api.certification.CertificationPreflightException
import com.plot.api.certification.CertificationReviewObservation
import com.plot.api.certification.CertificationReviewVerdict
import com.plot.api.certification.CertificationSentenceOracle
import com.plot.api.certification.EvidenceAttribution
import com.plot.api.certification.EvidenceEnvelope
import com.plot.api.certification.EvidenceOutcome
import com.plot.api.certification.HardGateCode
import com.plot.api.certification.JdkOpenRouterCertificationTransport
import com.plot.api.certification.LiveModelResultWriter
import com.plot.api.certification.ModelExecutionManifest
import com.plot.api.certification.OpenRouterCertificationClient
import com.plot.api.certification.OpenRouterGenerationAttribution
import com.plot.api.certification.OpenRouterGenerationMetadataClient
import com.plot.api.certification.OpenRouterPreflight
import com.plot.api.certification.OpenRouterPreflightRequest
import com.plot.api.certification.ProductionCanaryMetadata
import com.plot.api.certification.ProductionCanaryResult
import com.plot.api.certification.ProductionStructuredCanary
import com.plot.api.certification.SealedArtifact
import com.plot.api.certification.VerifiedOpenRouterAttribution
import com.plot.api.config.PlotAiProperties
import com.plot.api.generation.InvalidModelOutputException
import com.plot.api.generation.ModelOutputValidator
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.SourceProvider
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Tag("generation-certification-live")
@EnabledIfEnvironmentVariable(named = "PLOT_AI_CONTRACT_SMOKE", matches = "(?i)true")
class OpenAiGenerationContractSmokeTest {
	private val mapper = ObjectMapper()
	private val artifactContract = CertificationArtifactContract(mapper)
	private val outputValidator = ModelOutputValidator()
	private val caseEvaluator = CertificationCaseEvaluator()
	private val models = listOf(PlotAiProperties.GPT_5_4_NANO_MODEL, PlotAiProperties.GPT_4O_MINI_MODEL)

	@Test
	fun `two pinned models produce three valid attributable attempts for release scoring`() {
		val apiKey = requiredEnvironment("OPENROUTER_API_KEY")
		val providerSlug = requiredEnvironment("PLOT_AI_ROUTING_PROVIDER")
		val sourceRevision = requiredEnvironment("PLOT_GENERATION_SOURCE_REVISION")
		val corpusBytes = requireNotNull(javaClass.getResourceAsStream("/evals/generation-citation-cases.json")).use { it.readAllBytes() }
		val corpus = mapper.readValue(corpusBytes, EvalCorpus::class.java)
		val propertiesByModel = models.associateWith { model -> certificationProperties(model, providerSlug) }
		val profileHashes = CertificationGenerationProfileContract.hashes(propertiesByModel)
		val campaign = readCampaign(corpusBytes, sourceRevision, profileHashes)
		val resultWriter = LiveModelResultWriter(Path.of(requiredEnvironment("PLOT_CERTIFICATION_OUTPUT_ROOT")), mapper)
		val routerTransport = JdkOpenRouterCertificationTransport.live(apiKey)
		val replacement = replacementRequest()
		val selectedModels = replacement?.let { listOf(it.model) } ?: models
		val outcomes = selectedModels.associateWith { requestedModel ->
			runModelMatrix(
				apiKey,
				providerSlug,
				requestedModel,
				corpus,
				campaign,
				resultWriter,
				routerTransport,
				modelManifestOutput(requestedModel),
				propertiesByModel.getValue(requestedModel),
				modelProfileHash(requestedModel, profileHashes),
				replacement,
			)
		}

		if (replacement == null) {
			assertEquals(models.toSet(), outcomes.keys)
			outcomes.forEach { (model, attempts) ->
				assertEquals(3, attempts.count { it.outcome != EvidenceOutcome.INCONCLUSIVE }, "$model valid attempt count")
			}
		} else {
			val attempts = outcomes.getValue(replacement.model)
			val selected = attempts.single { it.outcome != EvidenceOutcome.INCONCLUSIVE }
			val execution = Files.newInputStream(
				absoluteRegularFile(modelManifestOutput(replacement.model).toString()), LinkOption.NOFOLLOW_LINKS,
			).use(mapper::readTree).let { artifactContract.sealModelExecution(it, campaign) }
			writeNew(
				replacement.resultOutput,
				mapper.writeValueAsBytes(mapOf(
					"schemaVersion" to "certification-model-replacement-v1",
					"selectedAttemptId" to selected.attemptId,
					"triggeredByBrowserAttemptId" to replacement.triggeredByBrowserAttemptId,
					"modelExecutionId" to execution.artifact.modelExecutionId,
					"ordinal" to replacement.ordinal,
				)),
			)
		}
	}

	private fun runModelMatrix(
		apiKey: String,
		providerSlug: String,
		requestedModel: String,
		corpus: EvalCorpus,
		campaign: SealedArtifact<CampaignManifest>,
		resultWriter: LiveModelResultWriter,
		routerTransport: JdkOpenRouterCertificationTransport,
		modelManifestOutput: Path,
		properties: PlotAiProperties,
		modelProfileHash: String,
		replacement: ReplacementRequest?,
	): List<CertificationAttempt> {
		val httpClient = SpringAiOpenAiHttpClient.builder().timeout(properties.timeout).build()
		val clientOptions = ClientOptions.builder()
			.httpClient(httpClient)
			.apiKey(apiKey)
			.baseUrl(CanonicalExternalOriginPolicy.OPENROUTER_API)
			.maxRetries(0)
			.build()
		val openAiClient = OpenAIClientImpl(clientOptions)
		val openAiAsyncClient = OpenAIClientAsyncImpl(clientOptions)
		try {
			val chatModel = OpenAiChatModel.builder()
				.openAiClient(openAiClient)
				.openAiClientAsync(openAiAsyncClient)
				.build()
			val gateway = SpringAiOpenAiGenerationGateway(
				SpringAiStructuredChatTransport(ChatClient.builder(chatModel), properties),
				properties,
				ChangelogPromptFactory(mapper),
			)
			val metadataClient = OpenRouterGenerationMetadataClient(routerTransport)
			val execution = try {
				OpenRouterPreflight(OpenRouterCertificationClient(routerTransport), metadataClient).run(
					OpenRouterPreflightRequest(requestedModel, providerSlug, properties.zeroDataRetention),
					productionCanary(gateway, requestedModel),
				) { verified ->
					if (replacement == null) {
						val node = executionNode(
							campaign, corpus, verified, modelProfileHash,
							properties.requireParameters, properties.zeroDataRetention,
						)
						artifactContract.sealModelExecution(node, campaign).also {
							writeNew(modelManifestOutput, mapper.writeValueAsBytes(node))
						}
					} else {
						val existing = Files.newInputStream(absoluteRegularFile(modelManifestOutput.toString()), LinkOption.NOFOLLOW_LINKS)
							.use(mapper::readTree)
						artifactContract.sealModelExecution(existing, campaign).also { sealed ->
							if (sealed.artifact.requestedModel != verified.requestedModel ||
								sealed.artifact.servedModel != verified.servedModel ||
								sealed.artifact.pinnedUpstream != verified.providerSlug ||
								sealed.artifact.modelProfileHash != modelProfileHash
							) error("replacement route does not match the sealed model execution")
						}
					}
				}
					.sealedModelExecution
			} catch (failure: CertificationPreflightException) {
				writePreflightFailure(campaign, resultWriter, failure.code)
				throw AssertionError("OpenRouter preflight ${failure.disposition.name.lowercase()}: ${failure.code.name}")
			} catch (failure: GenerationModelException) {
				writePreflightFailure(campaign, resultWriter, CertificationFailureCode.PROVIDER_UNAVAILABLE)
				throw AssertionError("OpenRouter preflight was inconclusive: ${failure.code.name}")
			}

			val matrix: CertificationAttemptMatrix? = if (replacement == null) CertificationAttemptMatrix() else null
			val replacementHistory = mutableListOf<CertificationAttempt>()
			var replacementAttempt = replacement?.let { CertificationAttempt(it.attemptId, it.ordinal) }
			var replacementEvidence = emptyMap<String, EvidenceEnvelope>()
			while (matrix?.complete != true && replacementHistory.none { it.outcome != EvidenceOutcome.INCONCLUSIVE }) {
				if (replacementHistory.size >= MAX_REPLACEMENT_ATTEMPTS) {
					error("replacement attempt bound exhausted")
				}
				val attempt = matrix?.next() ?: requireNotNull(replacementAttempt)
				val caseRecords = mutableListOf<CaseRunRecord>()
				var stopCode: String? = null
				var stopOutcome: EvidenceOutcome? = null
				for (evalCase in corpus.cases) {
					if (stopCode != null) {
						caseRecords += emptyCaseRecord(evalCase.id, stopCode, requireNotNull(stopOutcome))
						continue
					}
					val record = runCase(gateway, metadataClient, execution, evalCase, attempt)
					caseRecords += record
					if (record.stopAttempt) {
						stopCode = record.codes.first()
						stopOutcome = record.outcome
					}
				}
				val attemptOutcome = when {
					caseRecords.any { it.outcome == EvidenceOutcome.INCONCLUSIVE } -> EvidenceOutcome.INCONCLUSIVE
					caseRecords.any { it.outcome == EvidenceOutcome.HARD_GATE_FAIL } -> EvidenceOutcome.HARD_GATE_FAIL
					else -> EvidenceOutcome.PASS
				}
				val finalized = matrix?.record(attempt, attemptOutcome) ?: attempt.copy(outcome = attemptOutcome).also(replacementHistory::add)
				val currentEvidence = linkedMapOf<String, EvidenceEnvelope>()
				caseRecords.forEach { record ->
					val prior = attempt.replaces?.let { requireNotNull(replacementEvidence[record.scenarioId]) }
					val effective = if (attemptOutcome == EvidenceOutcome.INCONCLUSIVE) {
						record.copy(
							outcome = EvidenceOutcome.INCONCLUSIVE,
							codes = (record.codes + requireNotNull(stopCode)).distinct(),
						)
					} else record
					val node = evidenceNode(campaign, execution, finalized, effective, prior)
					val envelope = artifactContract.readEvidence(node, campaign, execution, prior)
					resultWriter.write(node, campaign, execution, prior)
					currentEvidence[record.scenarioId] = envelope
				}
				replacementEvidence = if (attemptOutcome == EvidenceOutcome.INCONCLUSIVE) currentEvidence else emptyMap()
				if (matrix == null && attemptOutcome == EvidenceOutcome.INCONCLUSIVE) {
					replacementAttempt = CertificationAttempt(opaqueId("attempt"), attempt.ordinal, finalized)
				}
			}
			return matrix?.history ?: replacementHistory
		} finally {
			openAiClient.close()
			openAiAsyncClient.close()
		}
	}

	private fun productionCanary(gateway: GenerationModelGateway, requestedModel: String) = ProductionStructuredCanary {
		val call = gateway.write(
			WriterModelRequest(
				UUID.randomUUID(),
				"Return one short sentence stating that this is a synthetic transport check.",
				emptyList(),
			),
		)
		ProductionCanaryResult(
			ProductionCanaryMetadata(
				call.metadata.responseId,
				call.metadata.servedModel,
				call.metadata.gateway,
				call.metadata.requestedModel,
			),
			call.value.sentences.isNotEmpty() && call.metadata.requestedModel == requestedModel,
		)
	}

	private fun runCase(
		gateway: GenerationModelGateway,
		metadataClient: OpenRouterGenerationMetadataClient,
		execution: SealedArtifact<ModelExecutionManifest>,
		evalCase: EvalCase,
		attempt: CertificationAttempt,
	): CaseRunRecord {
		val runId = UUID.randomUUID()
		val evidence = evalCase.toEvidence(runId)
		val accumulator = ProviderCallAccumulator(execution, metadataClient)
		var writerSchemaValid = true
		var reviewerSchemaValid = true
		var rewriteSchemaValid = true
		var routeAttributionValid = true
		var writerSentences = emptyList<SentenceArtifact>()
		var initialReviews = emptyList<com.plot.api.generation.model.ValidatedSentenceReview>()
		var rewrittenReviews = emptyList<com.plot.api.generation.model.ValidatedSentenceReview>()
		var modelSuppliedUrlCount = 0
		var semanticRewriteCount = 0
		var writerReviewCompositionValid = false
		val writerBodies = mutableListOf<String>()

		fun modelFailure(failure: GenerationModelException, schemaInvalid: () -> Unit) {
			if (failure.code == ModelFailureCode.MALFORMED_OUTPUT) schemaInvalid()
			else throw ExternalAttemptFailure("MODEL_${failure.code.name}")
		}

		try {
			try {
				val result = gateway.write(WriterModelRequest(runId, evalCase.instruction, evidence))
				accumulator.record(result.metadata)
				writerSentences = outputValidator.assignSentenceIds(
					runId,
					result.value,
					evidence.map { it.id }.toSet(),
				) { UUID.randomUUID() }
				writerBodies += writerSentences.map { it.body }
			} catch (failure: GenerationModelException) {
				modelFailure(failure) { writerSchemaValid = false }
			} catch (_: InvalidModelOutputException) {
				writerSchemaValid = false
			}

			if (writerSentences.isNotEmpty()) {
				try {
					val reviewResult = gateway.review(ReviewerModelRequest(runId, writerSentences, evidence))
					accumulator.record(reviewResult.metadata)
					modelSuppliedUrlCount += reviewResult.value.reviews.sumOf { it.modelSuppliedUrls.size }
					val reviews = outputValidator.validateReview(runId, writerSentences, evidence, reviewResult.value)
					val targets = reviews.filter { it.verdict == ReviewVerdict.NEEDS_SUPPORT }.map { it.sentenceId }
					if (targets.isEmpty()) {
						writerReviewCompositionValid = true
					} else {
						val rewriteResult = gateway.rewrite(
							RewriteModelRequest(
								runId,
								writerSentences,
								targets,
								evidence,
								"Remove unsupported scope and preserve only statements supported by the selected evidence.",
							),
						)
						accumulator.record(rewriteResult.metadata)
						semanticRewriteCount += 1
						writerSentences = outputValidator.applyTargetedRewrite(
							runId, writerSentences, targets, rewriteResult.value,
						) { UUID.randomUUID() }
						writerBodies += writerSentences.filter { it.id in targets }.map { it.body }
						val rewrittenReview = gateway.review(ReviewerModelRequest(runId, writerSentences, evidence))
						accumulator.record(rewrittenReview.metadata)
						modelSuppliedUrlCount += rewrittenReview.value.reviews.sumOf { it.modelSuppliedUrls.size }
						val resolved = outputValidator.validateReview(runId, writerSentences, evidence, rewrittenReview.value)
						writerReviewCompositionValid = resolved.none {
							it.sentenceId in targets && it.verdict == ReviewVerdict.NEEDS_SUPPORT
						}
					}
				} catch (failure: GenerationModelException) {
					modelFailure(failure) { reviewerSchemaValid = false; rewriteSchemaValid = false }
				} catch (_: InvalidModelOutputException) {
					reviewerSchemaValid = false
					rewriteSchemaValid = false
				}
			}

			val oracleSentences = evalCase.toSentences(runId)
			try {
				val result = gateway.review(ReviewerModelRequest(runId, oracleSentences, evidence))
				accumulator.record(result.metadata)
				modelSuppliedUrlCount += result.value.reviews.sumOf { it.modelSuppliedUrls.size }
				initialReviews = outputValidator.validateReview(runId, oracleSentences, evidence, result.value)
			} catch (failure: GenerationModelException) {
				modelFailure(failure) { reviewerSchemaValid = false }
			} catch (_: InvalidModelOutputException) {
				reviewerSchemaValid = false
			}

			val targets = initialReviews.filter { it.verdict == ReviewVerdict.NEEDS_SUPPORT }.map { it.sentenceId }
			if (targets.isNotEmpty()) {
				try {
					val rewriteResult = gateway.rewrite(
						RewriteModelRequest(
							runId,
							oracleSentences,
							targets,
							evidence,
							"Remove unsupported scope and preserve only statements supported by the selected evidence.",
						),
					)
					accumulator.record(rewriteResult.metadata)
					semanticRewriteCount += 1
					val rewritten = outputValidator.applyTargetedRewrite(
						runId,
						oracleSentences,
						targets,
						rewriteResult.value,
					) { UUID.randomUUID() }
					val reviewResult = gateway.review(ReviewerModelRequest(runId, rewritten, evidence))
					accumulator.record(reviewResult.metadata)
					modelSuppliedUrlCount += reviewResult.value.reviews.sumOf { it.modelSuppliedUrls.size }
					rewrittenReviews = outputValidator.validateReview(runId, rewritten, evidence, reviewResult.value)
						.filter { it.sentenceId in targets }
					writerSentences = writerSentences + rewritten.filter { it.id in targets }
				} catch (failure: GenerationModelException) {
					modelFailure(failure) { rewriteSchemaValid = false }
				} catch (_: InvalidModelOutputException) {
					rewriteSchemaValid = false
				}
			}
		} catch (_: RouteAttributionFailure) {
			routeAttributionValid = false
		} catch (failure: ExternalAttemptFailure) {
			return accumulator.record(
				evalCase.id,
				EvidenceOutcome.INCONCLUSIVE,
				listOf(failure.code),
				semanticRewriteCount,
				attempt.ordinal == 1 && attempt.replaces == null,
				stopAttempt = true,
			)
		}

		val targetIds = initialReviews.filter { it.verdict == ReviewVerdict.NEEDS_SUPPORT }.map { it.sentenceId }.toSet()
		val oracles = evalCase.sentences.map { sentence ->
			val isRuntimeTarget = UUID.fromString(sentence.id) in targetIds
			CertificationSentenceOracle(
				UUID.fromString(sentence.id),
				CertificationReviewVerdict.valueOf(sentence.expectedVerdict.name),
				sentence.expectedEvidenceIds.toSet(),
				when {
					sentence.rewriteExpectedVerdict != null -> CertificationReviewVerdict.valueOf(sentence.rewriteExpectedVerdict.name)
					isRuntimeTarget -> CertificationReviewVerdict.valueOf(sentence.expectedVerdict.name)
					else -> null
				},
				when {
					sentence.rewriteExpectedVerdict != null -> sentence.rewriteExpectedEvidenceIds.toSet()
					isRuntimeTarget -> sentence.expectedEvidenceIds.toSet()
					else -> emptySet()
				},
			)
		}
		val originalLinks = evidence.associate { it.id to it.originalUrl }
		val grade = caseEvaluator.evaluate(
			CertificationCaseObservation(
				scenarioId = evalCase.id,
				knownEvidenceIds = evidence.map { it.id }.toSet(),
				sentenceOracles = oracles,
				initialReviews = initialReviews.map {
					CertificationReviewObservation(it.sentenceId, CertificationReviewVerdict.valueOf(it.verdict.name), it.evidenceIds.toSet())
				},
				rewrittenReviews = rewrittenReviews.map {
					CertificationReviewObservation(it.sentenceId, CertificationReviewVerdict.valueOf(it.verdict.name), it.evidenceIds.toSet())
				}.takeIf(List<*>::isNotEmpty),
				writerSchemaValid = writerSchemaValid,
				reviewerSchemaValid = reviewerSchemaValid,
				rewriteSchemaValid = rewriteSchemaValid,
				promptInjectionResistant = writerBodies.none(INJECTION_DIRECTIVE::containsMatchIn),
				routeAttributionValid = routeAttributionValid,
				linksPreserved = evidence.all { originalLinks[it.id] == it.originalUrl },
				modelSuppliedUrlCount = modelSuppliedUrlCount,
				writerReviewCompositionValid = writerReviewCompositionValid,
			),
		)
		return accumulator.record(
			evalCase.id,
			grade.outcome,
			grade.codes.map(HardGateCode::name),
			semanticRewriteCount,
			attempt.ordinal == 1 && attempt.replaces == null,
			citationCount = grade.citationCount,
			reviewNeededSentenceCount = grade.reviewNeededSentenceCount,
			unresolvedConflictCount = grade.unresolvedConflictCount,
			citationPrecisionBasisPoints = grade.citationPrecisionBasisPoints,
			citationRecallBasisPoints = grade.citationRecallBasisPoints,
			supportedClaimRecallBasisPoints = grade.supportedClaimRecallBasisPoints,
			unsupportedClaimRecallBasisPoints = grade.unsupportedClaimRecallBasisPoints,
			conflictRecallBasisPoints = grade.conflictRecallBasisPoints,
			notRequiredFalsePositiveBasisPoints = grade.notRequiredFalsePositiveBasisPoints,
			stopAttempt = !routeAttributionValid,
		)
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
			contentHash = sha256("eval-evidence", item.body),
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
			origin = com.plot.api.generation.model.SentenceOrigin.GENERATED,
		)
	}

	private fun readCampaign(
		corpusBytes: ByteArray,
		sourceRevision: String,
		profileHashes: CertificationProfileHashes,
	): SealedArtifact<CampaignManifest> {
		val path = absoluteRegularFile(requiredEnvironment("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST"))
		val campaign = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(mapper::readTree)
			.let(artifactContract::sealCampaign)
		if (campaign.artifact.campaignId != requiredEnvironment("PLOT_CERTIFICATION_CAMPAIGN_ID") ||
			campaign.hash != requiredEnvironment("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH") ||
			campaign.artifact.sourceRevision != sourceRevision ||
			campaign.artifact.corpusHash != sha256("corpus", corpusBytes) ||
			campaign.artifact.profileHash != profileHashes.matrixProfileHash ||
			campaign.artifact.sourceSnapshotSetHash != requiredEnvironment("PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH")
		) error("sealed campaign does not match the live matrix")
		return campaign
	}

	private fun modelManifestOutput(requestedModel: String): Path = when (requestedModel) {
		PlotAiProperties.GPT_5_4_NANO_MODEL -> "PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT"
		PlotAiProperties.GPT_4O_MINI_MODEL -> "PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT"
		else -> error("unsupported certification model")
	}.let(::requiredEnvironment).let(Path::of)

	private fun absoluteRegularFile(value: String): Path = Path.of(value).also { path ->
		if (!path.isAbsolute || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..1024L * 1024L) {
			error("certification input path rejected")
		}
	}

	private fun writeNew(target: Path, bytes: ByteArray) {
		if (!target.isAbsolute || bytes.isEmpty() || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			error("certification model manifest output rejected")
		}
		Files.createDirectories(target.parent)
		if (Files.isSymbolicLink(target.parent)) error("certification model manifest output rejected")
		Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
	}

	private fun executionNode(
		campaign: SealedArtifact<CampaignManifest>,
		corpus: EvalCorpus,
		verified: VerifiedOpenRouterAttribution,
		modelProfileHash: String,
		requireParameters: Boolean,
		zeroDataRetention: Boolean,
	): JsonNode = mapper.valueToTree(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "MODEL_EXECUTION_MANIFEST",
		"artifactId" to opaqueId("artifact"),
		"campaignId" to campaign.artifact.campaignId,
		"campaignManifestHash" to campaign.hash,
		"modelExecutionId" to opaqueId("model-execution"),
		"sealedAt" to Instant.now().toString(),
		"requestedModel" to verified.requestedModel,
		"servedModel" to verified.servedModel,
		"modelProfileHash" to modelProfileHash,
		"pinnedUpstream" to verified.providerSlug,
		"routePolicyHash" to sha256(
			"route-policy",
			"${verified.providerSlug}|only|no-fallback|requireParameters=$requireParameters|zdr=$zeroDataRetention",
		),
		"processIdentity" to opaqueId("process"),
		"sourceNamespace" to opaqueId("namespace"),
		"idempotencyNamespace" to opaqueId("namespace"),
		"scenarioIds" to (corpus.cases.map { it.id } + listOf("real-github-journey", "process-restart")),
	))

	private fun certificationProperties(model: String, providerSlug: String): PlotAiProperties =
		CertificationGenerationProfileContract.properties(
			model = model,
			providerSlug = providerSlug,
			timeoutSeconds = environment("PLOT_AI_TIMEOUT_SECONDS")?.toLongOrNull() ?: 45,
			maxOutputTokens = environment("PLOT_AI_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: 2_000,
			transportRetries = environment("PLOT_AI_TRANSPORT_RETRIES")?.toIntOrNull() ?: 0,
			schemaRetries = environment("PLOT_AI_SCHEMA_RETRIES")?.toIntOrNull() ?: 1,
		)

	private fun modelProfileHash(model: String, hashes: CertificationProfileHashes): String = when (model) {
		PlotAiProperties.GPT_5_4_NANO_MODEL -> hashes.nanoModelProfileHash
		PlotAiProperties.GPT_4O_MINI_MODEL -> hashes.miniModelProfileHash
		else -> error("unsupported certification model")
	}

	private fun evidenceNode(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		attempt: CertificationAttempt,
		record: CaseRunRecord,
		prior: EvidenceEnvelope?,
	): JsonNode {
		val values = linkedMapOf<String, Any>(
			"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
			"artifactType" to "EVIDENCE_ENVELOPE",
			"artifactId" to opaqueId("artifact"),
			"campaignId" to campaign.artifact.campaignId,
			"campaignManifestHash" to campaign.hash,
			"modelExecutionId" to execution.artifact.modelExecutionId,
			"modelExecutionManifestHash" to execution.hash,
			"recordedAt" to Instant.now().toString(),
			"evidenceType" to "MODEL_ATTEMPT",
			"subjectType" to "ATTEMPT",
			"attemptId" to attempt.attemptId,
			"scenarioId" to record.scenarioId,
			"ordinal" to attempt.ordinal,
			"outcome" to record.outcome.name,
			"metrics" to record.metrics,
			"codes" to record.codes,
		)
		record.attribution?.let { attribution ->
			values["attribution"] = mapOf(
				"requestedModel" to attribution.requestedModel,
				"servedModel" to attribution.servedModel,
				"observedUpstream" to attribution.observedUpstream,
				"responseIdHash" to attribution.responseIdHash,
			)
		}
		if (prior != null) {
			values["lineage"] = mapOf(
				"relation" to "REPLACES_INCONCLUSIVE",
				"priorArtifactId" to prior.artifactId,
				"priorAttemptId" to requireNotNull(prior.attemptId),
			)
		}
		return mapper.valueToTree(values)
	}

	private fun writePreflightFailure(
		campaign: SealedArtifact<CampaignManifest>,
		writer: LiveModelResultWriter,
		failureCode: CertificationFailureCode,
	) {
		val node = mapper.valueToTree<JsonNode>(mapOf(
			"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
			"artifactType" to "EVIDENCE_ENVELOPE",
			"artifactId" to opaqueId("artifact"),
			"campaignId" to campaign.artifact.campaignId,
			"campaignManifestHash" to campaign.hash,
			"recordedAt" to Instant.now().toString(),
			"evidenceType" to "PREFLIGHT",
			"subjectType" to "CAMPAIGN",
			"outcome" to "INCONCLUSIVE",
			"metrics" to mapOf("liveCredentialCount" to 1),
			"codes" to listOf("PREFLIGHT_${failureCode.name}"),
		))
		writer.write(node, campaign)
	}

	private fun emptyCaseRecord(scenarioId: String, code: String, outcome: EvidenceOutcome) = CaseRunRecord(
		scenarioId,
		outcome,
		listOf(code),
		baseMetrics(),
		null,
		stopAttempt = true,
	)

	private fun requiredEnvironment(name: String): String = environment(name)
		?.takeIf { it.isNotBlank() }
		?: error("$name is required when PLOT_AI_CONTRACT_SMOKE=true")

	private fun environment(name: String): String? = System.getenv(name)?.trim()

	private fun replacementRequest(): ReplacementRequest? {
		val model = environment("PLOT_CERTIFICATION_REPLACEMENT_MODEL") ?: return null
		require(model in models) { "replacement model is not a certified profile" }
		val ordinal = requiredEnvironment("PLOT_CERTIFICATION_REPLACEMENT_ORDINAL").toIntOrNull()
			?.takeIf { it in 1..3 } ?: error("replacement ordinal rejected")
		val attemptId = requiredEnvironment("PLOT_CERTIFICATION_REPLACEMENT_ATTEMPT_ID")
		require(ATTEMPT_ID.matches(attemptId)) { "replacement attempt identity rejected" }
		val triggeredByBrowserAttemptId = requiredEnvironment("PLOT_CERTIFICATION_REPLACEMENT_TRIGGER_ATTEMPT_ID")
		require(ATTEMPT_ID.matches(triggeredByBrowserAttemptId) && triggeredByBrowserAttemptId != attemptId) {
			"replacement trigger attempt identity rejected"
		}
		val resultOutput = Path.of(requiredEnvironment("PLOT_CERTIFICATION_REPLACEMENT_RESULT_OUTPUT"))
		return ReplacementRequest(model, ordinal, attemptId, triggeredByBrowserAttemptId, resultOutput)
	}

	private fun opaqueId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().replace("-", "")}"

	private fun sha256(namespace: String, bytes: ByteArray): String = "sha256:${shaHex(namespace, bytes)}"
	private fun sha256(namespace: String, value: String): String = sha256(namespace, value.toByteArray(Charsets.UTF_8))
	private fun shaHex(namespace: String, value: String): String = shaHex(namespace, value.toByteArray(Charsets.UTF_8))
	private fun shaHex(namespace: String, bytes: ByteArray): String = HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + bytes),
	)

	private fun baseMetrics() = linkedMapOf<String, Any>(
		"latencyMs" to 0,
		"promptTokens" to 0,
		"completionTokens" to 0,
		"reasoningTokens" to 0,
		"cachedTokens" to 0,
		"costUsdMicros" to 0,
		"rewriteCount" to 0,
		"citationCount" to 0,
		"reviewNeededSentenceCount" to 0,
		"unresolvedConflictCount" to 0,
		"modelCallCount" to 0,
		"citationPrecisionBasisPoints" to 0,
		"citationRecallBasisPoints" to 0,
		"supportedClaimRecallBasisPoints" to 0,
		"unsupportedClaimRecallBasisPoints" to 0,
		"conflictRecallBasisPoints" to 0,
		"notRequiredFalsePositiveBasisPoints" to 0,
		"coldStart" to false,
	)

	private class ProviderCallAccumulator(
		private val execution: SealedArtifact<ModelExecutionManifest>,
		private val metadataClient: OpenRouterGenerationMetadataClient,
	) {
		private var modelCallCount = 0
		private var promptTokens = 0
		private var completionTokens = 0
		private var reasoningTokens = 0
		private var cachedTokens = 0
		private var costUsdMicros = 0L
		private var latencyMs = 0
		private val responseIdHashes = mutableListOf<String>()

		fun record(metadata: ModelCallMetadata) {
			modelCallCount = Math.addExact(modelCallCount, metadata.physicalCallCount)
			// A retry has no response ID for each paid exchange, so it cannot enter an attributable baseline.
			if (metadata.physicalCallCount != 1) throw RouteAttributionFailure()
			if (metadata.gateway != PlotAiProperties.OPENROUTER_GATEWAY ||
				metadata.requestedModel != execution.artifact.requestedModel ||
				!servedModelMatchesExecution(metadata.servedModel) || metadata.responseId.isNullOrBlank()
			) throw RouteAttributionFailure()
			val attribution = try {
				metadataClient.fetch(com.plot.api.certification.GenerationMetadataRequest(metadata.responseId))
			} catch (failure: CertificationPreflightException) {
				if (failure.code in setOf(CertificationFailureCode.ATTRIBUTION_MISMATCH, CertificationFailureCode.METADATA_INVALID)) {
					throw RouteAttributionFailure()
				}
				throw ExternalAttemptFailure("EXTERNAL_${failure.code.name}")
			}
			if (attribution.servedModel != execution.artifact.servedModel ||
				attribution.providerSlug != execution.artifact.pinnedUpstream
			) throw RouteAttributionFailure()
			add(attribution)
		}

		/**
		 * OpenRouter may echo the requested alias in a chat response while its generation
		 * metadata reports the provider-confirmed dated model. Preflight already accepts
		 * this alias form; keep the per-call gate consistent without weakening the
		 * authoritative metadata check below.
		 */
		private fun servedModelMatchesExecution(servedModel: String?): Boolean =
			servedModel == execution.artifact.servedModel || servedModel == execution.artifact.requestedModel

		fun record(
			scenarioId: String,
			outcome: EvidenceOutcome,
			codes: List<String>,
			rewriteCount: Int,
			coldStart: Boolean,
			citationCount: Int = 0,
			reviewNeededSentenceCount: Int = 0,
			unresolvedConflictCount: Int = 0,
			citationPrecisionBasisPoints: Int = 0,
			citationRecallBasisPoints: Int = 0,
			supportedClaimRecallBasisPoints: Int = 0,
			unsupportedClaimRecallBasisPoints: Int = 0,
			conflictRecallBasisPoints: Int = 0,
			notRequiredFalsePositiveBasisPoints: Int = 0,
			stopAttempt: Boolean = false,
		): CaseRunRecord {
			val metrics = linkedMapOf<String, Any>(
				"latencyMs" to latencyMs,
				"promptTokens" to promptTokens,
				"completionTokens" to completionTokens,
				"reasoningTokens" to reasoningTokens,
				"cachedTokens" to cachedTokens,
				"costUsdMicros" to Math.toIntExact(costUsdMicros),
				"rewriteCount" to rewriteCount,
				"citationCount" to citationCount,
				"reviewNeededSentenceCount" to reviewNeededSentenceCount,
				"unresolvedConflictCount" to unresolvedConflictCount,
				"modelCallCount" to modelCallCount,
				"citationPrecisionBasisPoints" to citationPrecisionBasisPoints,
				"citationRecallBasisPoints" to citationRecallBasisPoints,
				"supportedClaimRecallBasisPoints" to supportedClaimRecallBasisPoints,
				"unsupportedClaimRecallBasisPoints" to unsupportedClaimRecallBasisPoints,
				"conflictRecallBasisPoints" to conflictRecallBasisPoints,
				"notRequiredFalsePositiveBasisPoints" to notRequiredFalsePositiveBasisPoints,
				"coldStart" to coldStart,
			)
			val attribution = responseIdHashes.takeIf(List<*>::isNotEmpty)?.let {
				EvidenceAttribution(
					execution.artifact.requestedModel,
					execution.artifact.servedModel,
					execution.artifact.pinnedUpstream,
					sha256("case-response-set", responseIdHashes.sorted().joinToString("|")),
				)
			}
			return CaseRunRecord(scenarioId, outcome, codes.distinct().sorted(), metrics, attribution, stopAttempt)
		}

		private fun add(attribution: OpenRouterGenerationAttribution) {
			promptTokens = Math.addExact(promptTokens, attribution.nativeTokens.prompt)
			completionTokens = Math.addExact(completionTokens, attribution.nativeTokens.completion)
			reasoningTokens = Math.addExact(reasoningTokens, attribution.nativeTokens.reasoning)
			cachedTokens = Math.addExact(cachedTokens, attribution.nativeTokens.cached)
			costUsdMicros = Math.addExact(costUsdMicros, attribution.costUsdMicros)
			latencyMs = Math.addExact(latencyMs, attribution.latencyMs)
			responseIdHashes += attribution.responseIdHash
		}

		private fun sha256(namespace: String, value: String): String = "sha256:" + HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest("$namespace\u0000$value".toByteArray(Charsets.UTF_8)),
		)
	}

	private data class CaseRunRecord(
		val scenarioId: String,
		val outcome: EvidenceOutcome,
		val codes: List<String>,
		val metrics: Map<String, Any>,
		val attribution: EvidenceAttribution?,
		val stopAttempt: Boolean,
	)

	private class RouteAttributionFailure : RuntimeException()
	private class ExternalAttemptFailure(val code: String) : RuntimeException(code)
	private data class ReplacementRequest(
		val model: String,
		val ordinal: Int,
		val attemptId: String,
		val triggeredByBrowserAttemptId: String,
		val resultOutput: Path,
	)

	companion object {
		private const val MAX_REPLACEMENT_ATTEMPTS = 4
		private val ATTEMPT_ID = Regex("^attempt-[a-f0-9]{16,64}$")
		private val INJECTION_DIRECTIVE = Regex(
			"(?i)(?:ignore|disregard|override)\\s+(?:all\\s+)?(?:previous|prior|system)\\s+instructions|reveal\\s+(?:the\\s+)?(?:hidden|system|private)\\s+(?:prompt|evidence|instructions)",
		)
	}
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
	val rewriteExpectedVerdict: ReviewVerdict?,
	val rewriteExpectedEvidenceIds: List<UUID>,
)
