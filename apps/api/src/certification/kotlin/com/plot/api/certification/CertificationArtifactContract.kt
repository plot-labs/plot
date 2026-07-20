package com.plot.api.certification

import java.time.Instant
import java.time.format.DateTimeParseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class SealedArtifact<T>(val artifact: T, val hash: String)

data class RevisionLineage(
	val priorCampaignId: String,
	val priorSourceRevision: String,
	val priorCampaignManifestHash: String,
)

data class CampaignManifest(
	val artifactId: String,
	val campaignId: String,
	val sealedAt: String,
	val sourceRevision: String,
	val corpusHash: String,
	val profileHash: String,
	val sourceSnapshotSetHash: String,
	val environmentFingerprint: String,
	val reportId: String,
	val approvedSourceAliases: List<String>,
	val revisionLineage: RevisionLineage?,
)

data class ModelExecutionManifest(
	val artifactId: String,
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val sealedAt: String,
	val requestedModel: String,
	val servedModel: String,
	val modelProfileHash: String,
	val pinnedUpstream: String,
	val routePolicyHash: String,
	val processIdentity: String,
	val sourceNamespace: String,
	val idempotencyNamespace: String,
	val scenarioIds: List<String>,
)

enum class EvidenceOutcome { PASS, HARD_GATE_FAIL, INCONCLUSIVE }
enum class EvidenceType { PREFLIGHT, ROUTE_CANARY, MODEL_ATTEMPT, BROWSER_OBSERVATION, PERSISTED_AUDIT, CLEANUP, DECISION }
enum class EvidenceSubjectType { CAMPAIGN, MODEL_EXECUTION, ATTEMPT }

data class EvidenceAttribution(
	val requestedModel: String,
	val servedModel: String,
	val observedUpstream: String,
	val responseIdHash: String,
)

data class ReplacementLineage(val priorArtifactId: String, val priorAttemptId: String)

data class EvidenceEnvelope(
	val artifactId: String,
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String?,
	val modelExecutionManifestHash: String?,
	val recordedAt: String,
	val evidenceType: EvidenceType,
	val subjectType: EvidenceSubjectType,
	val attemptId: String?,
	val scenarioId: String?,
	val ordinal: Int?,
	val outcome: EvidenceOutcome,
	val metrics: Map<String, Any>,
	val codes: List<String>,
	val attribution: EvidenceAttribution?,
	val lineage: ReplacementLineage?,
)

class CertificationArtifactViolation(message: String) : IllegalArgumentException(message)

class CertificationArtifactContract(private val mapper: ObjectMapper = ObjectMapper()) {
	fun sealCampaign(node: JsonNode, prior: SealedArtifact<CampaignManifest>? = null): SealedArtifact<CampaignManifest> {
		exactFields(node, CAMPAIGN_FIELDS, "campaign manifest")
		literal(node, "schemaVersion", SCHEMA_VERSION)
		literal(node, "artifactType", "CAMPAIGN_MANIFEST")
		val lineage = node.optional("revisionLineage").map { parseRevisionLineage(it) }.orElse(null)
		val artifact = CampaignManifest(
			artifactId = patternedField(node, "artifactId", ARTIFACT_ID),
			campaignId = patternedField(node, "campaignId", CAMPAIGN_ID),
			sealedAt = timestamp(node, "sealedAt"),
			sourceRevision = patternedField(node, "sourceRevision", REVISION),
			corpusHash = patternedField(node, "corpusHash", HASH),
			profileHash = patternedField(node, "profileHash", HASH),
			sourceSnapshotSetHash = patternedField(node, "sourceSnapshotSetHash", HASH),
			environmentFingerprint = patternedField(node, "environmentFingerprint", HASH),
			reportId = patternedField(node, "reportId", REPORT_ID),
			approvedSourceAliases = stringList(node, "approvedSourceAliases", SOURCE_ALIAS, true),
			revisionLineage = lineage,
		)
		if (lineage != null) {
			val sealedPrior = prior ?: violation("revision lineage requires the sealed prior campaign")
			if (lineage.priorCampaignId != sealedPrior.artifact.campaignId ||
				lineage.priorSourceRevision != sealedPrior.artifact.sourceRevision ||
				lineage.priorCampaignManifestHash != sealedPrior.hash
			) violation("revision lineage does not match the sealed prior campaign")
			if (artifact.campaignId == sealedPrior.artifact.campaignId || artifact.sourceRevision == sealedPrior.artifact.sourceRevision) {
				violation("a superseding revision must have a new campaign and source revision")
			}
		} else if (prior != null) {
			violation("prior campaign supplied without explicit revision lineage")
		}
		return SealedArtifact(artifact, hash(node))
	}

	fun sealModelExecution(
		node: JsonNode,
		campaign: SealedArtifact<CampaignManifest>,
	): SealedArtifact<ModelExecutionManifest> {
		exactFields(node, EXECUTION_FIELDS, "model execution manifest")
		literal(node, "schemaVersion", SCHEMA_VERSION)
		literal(node, "artifactType", "MODEL_EXECUTION_MANIFEST")
		val scenarioIds = stringList(node, "scenarioIds", SCENARIO_ID, true)
		val artifact = ModelExecutionManifest(
			artifactId = patternedField(node, "artifactId", ARTIFACT_ID),
			campaignId = patternedField(node, "campaignId", CAMPAIGN_ID),
			campaignManifestHash = patternedField(node, "campaignManifestHash", HASH),
			modelExecutionId = patternedField(node, "modelExecutionId", MODEL_EXECUTION_ID),
			sealedAt = timestamp(node, "sealedAt"),
			requestedModel = patternedField(node, "requestedModel", MODEL_ID),
			servedModel = patternedField(node, "servedModel", MODEL_ID),
			modelProfileHash = patternedField(node, "modelProfileHash", HASH),
			pinnedUpstream = patternedField(node, "pinnedUpstream", PROVIDER_SLUG),
			routePolicyHash = patternedField(node, "routePolicyHash", HASH),
			processIdentity = patternedField(node, "processIdentity", PROCESS_ID),
			sourceNamespace = patternedField(node, "sourceNamespace", NAMESPACE),
			idempotencyNamespace = patternedField(node, "idempotencyNamespace", NAMESPACE),
			scenarioIds = scenarioIds,
		)
		if (artifact.campaignId != campaign.artifact.campaignId) violation("model execution references another campaign")
		if (artifact.campaignManifestHash != campaign.hash) violation("model execution campaign manifest hash mismatch")
		return SealedArtifact(artifact, hash(node))
	}

	fun readEvidence(
		node: JsonNode,
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>? = null,
		prior: EvidenceEnvelope? = null,
	): EvidenceEnvelope {
		exactFields(node, EVIDENCE_FIELDS, "evidence envelope")
		literal(node, "schemaVersion", SCHEMA_VERSION)
		literal(node, "artifactType", "EVIDENCE_ENVELOPE")
		val artifact = EvidenceEnvelope(
			artifactId = patternedField(node, "artifactId", ARTIFACT_ID),
			campaignId = patternedField(node, "campaignId", CAMPAIGN_ID),
			campaignManifestHash = patternedField(node, "campaignManifestHash", HASH),
			modelExecutionId = optionalPatterned(node, "modelExecutionId", MODEL_EXECUTION_ID),
			modelExecutionManifestHash = optionalPatterned(node, "modelExecutionManifestHash", HASH),
			recordedAt = timestamp(node, "recordedAt"),
			evidenceType = enumValue<EvidenceType>(node, "evidenceType"),
			subjectType = enumValue<EvidenceSubjectType>(node, "subjectType"),
			attemptId = optionalPatterned(node, "attemptId", ATTEMPT_ID),
			scenarioId = optionalPatterned(node, "scenarioId", SCENARIO_ID),
			ordinal = node.optional("ordinal").map { integerValue(it, "ordinal", 1, 3) }.orElse(null),
			outcome = enumValue<EvidenceOutcome>(node, "outcome"),
			metrics = parseMetrics(node.required("metrics")),
			codes = stringList(node, "codes", CODE, false),
			attribution = node.optional("attribution").map { parseAttribution(it) }.orElse(null),
			lineage = node.optional("lineage").map { parseReplacementLineage(it) }.orElse(null),
		)
		if (artifact.campaignId != campaign.artifact.campaignId) violation("evidence references another campaign")
		if (artifact.campaignManifestHash != campaign.hash) violation("evidence campaign manifest hash mismatch")
		validateEvidenceSubject(artifact, execution)
		validateReplacement(artifact, prior)
		return artifact
	}

	fun validateBundle(
		campaign: SealedArtifact<CampaignManifest>,
		executions: List<SealedArtifact<ModelExecutionManifest>>,
		evidence: List<EvidenceEnvelope>,
	) {
		val artifactIds = listOf(campaign.artifact.artifactId) + executions.map { it.artifact.artifactId } + evidence.map { it.artifactId }
		if (artifactIds.distinct().size != artifactIds.size) violation("duplicate artifact identity")
		val executionIds = executions.map { it.artifact.modelExecutionId }
		if (executionIds.distinct().size != executionIds.size) violation("duplicate model execution identity")
		executions.forEach {
			if (it.artifact.campaignId != campaign.artifact.campaignId || it.artifact.campaignManifestHash != campaign.hash) {
				violation("bundle contains a cross-campaign model execution")
			}
		}
		val executionsById = executions.associateBy { it.artifact.modelExecutionId }
		evidence.forEach { envelope ->
			if (envelope.campaignId != campaign.artifact.campaignId || envelope.campaignManifestHash != campaign.hash) {
				violation("bundle contains cross-campaign evidence")
			}
			if (envelope.subjectType == EvidenceSubjectType.CAMPAIGN) {
				if (envelope.modelExecutionId != null || envelope.modelExecutionManifestHash != null) {
					violation("campaign evidence cannot reference a model execution")
				}
			} else {
				val executionId = envelope.modelExecutionId ?: violation("bundle evidence is missing its model execution")
				val execution = executionsById[executionId] ?: violation("bundle evidence references a model execution that is not present")
				if (envelope.modelExecutionManifestHash != execution.hash) {
					violation("bundle evidence model execution manifest hash mismatch")
				}
			}
		}
	}

	private fun validateEvidenceSubject(artifact: EvidenceEnvelope, execution: SealedArtifact<ModelExecutionManifest>?) {
		if (artifact.subjectType == EvidenceSubjectType.CAMPAIGN) {
			if (artifact.evidenceType !in setOf(EvidenceType.PREFLIGHT, EvidenceType.CLEANUP, EvidenceType.DECISION) ||
				artifact.modelExecutionId != null || artifact.modelExecutionManifestHash != null || artifact.attemptId != null ||
				artifact.scenarioId != null || artifact.ordinal != null || artifact.attribution != null || artifact.lineage != null
			) violation("campaign evidence contains model or attempt fields")
			if (execution != null) violation("campaign evidence must not be parsed against a model execution")
			return
		}
		val sealedExecution = execution ?: violation("model and attempt evidence require a sealed model execution")
		if (artifact.modelExecutionId != sealedExecution.artifact.modelExecutionId) violation("evidence references another model execution")
		if (artifact.modelExecutionManifestHash != sealedExecution.hash) violation("evidence model execution manifest hash mismatch")
		if (artifact.subjectType == EvidenceSubjectType.MODEL_EXECUTION) {
			if (artifact.evidenceType != EvidenceType.ROUTE_CANARY || artifact.attemptId != null || artifact.scenarioId != null ||
				artifact.ordinal != null || artifact.lineage != null || artifact.attribution == null
			) {
				violation("invalid model execution evidence fields")
			}
		} else {
			if (artifact.evidenceType !in setOf(EvidenceType.MODEL_ATTEMPT, EvidenceType.BROWSER_OBSERVATION, EvidenceType.PERSISTED_AUDIT) ||
				artifact.attemptId == null || artifact.scenarioId == null || artifact.ordinal == null
			) violation("attempt evidence is missing its sealed attempt identity")
			if (artifact.scenarioId !in sealedExecution.artifact.scenarioIds) {
				violation("evidence references an unsealed scenario")
			}
		}
		artifact.attribution?.let {
			if (it.requestedModel != sealedExecution.artifact.requestedModel) violation("attempt mixes model profiles")
			if (it.servedModel != sealedExecution.artifact.servedModel) violation("attempt served model does not match the sealed served model")
			if (it.observedUpstream != sealedExecution.artifact.pinnedUpstream) violation("attempt mixes pinned routes")
		}
	}

	private fun validateReplacement(artifact: EvidenceEnvelope, prior: EvidenceEnvelope?) {
		val lineage = artifact.lineage
		if (lineage == null) {
			if (prior != null) violation("prior evidence supplied without explicit replacement lineage")
			return
		}
		val previous = prior ?: violation("replacement lineage requires prior evidence")
		if (previous.outcome != EvidenceOutcome.INCONCLUSIVE) violation("only inconclusive evidence can be replaced")
		if (lineage.priorArtifactId != previous.artifactId || lineage.priorAttemptId != previous.attemptId) {
			violation("replacement lineage does not match prior evidence")
		}
		if (artifact.campaignId != previous.campaignId || artifact.modelExecutionId != previous.modelExecutionId) {
			violation("replacement lineage cannot cross campaign or model execution")
		}
		if (artifact.attemptId == previous.attemptId) violation("replacement must use a new attempt id")
		if (artifact.scenarioId != previous.scenarioId || artifact.ordinal != previous.ordinal) {
			violation("replacement must preserve the same scenario and ordinal")
		}
	}

	private fun parseRevisionLineage(node: JsonNode): RevisionLineage {
		exactFields(node, REVISION_LINEAGE_FIELDS, "revisionLineage")
		literal(node, "relation", "SUPERSEDES_REVISION")
		return RevisionLineage(
			patternedField(node, "priorCampaignId", CAMPAIGN_ID),
			patternedField(node, "priorSourceRevision", REVISION),
			patternedField(node, "priorCampaignManifestHash", HASH),
		)
	}

	private fun parseAttribution(node: JsonNode): EvidenceAttribution {
		exactFields(node, ATTRIBUTION_FIELDS, "attribution")
		return EvidenceAttribution(
			patternedField(node, "requestedModel", MODEL_ID),
			patternedField(node, "servedModel", MODEL_ID),
			patternedField(node, "observedUpstream", PROVIDER_SLUG),
			patternedField(node, "responseIdHash", HASH),
		)
	}

	private fun parseReplacementLineage(node: JsonNode): ReplacementLineage {
		exactFields(node, REPLACEMENT_FIELDS, "lineage")
		literal(node, "relation", "REPLACES_INCONCLUSIVE")
		return ReplacementLineage(patternedField(node, "priorArtifactId", ARTIFACT_ID), patternedField(node, "priorAttemptId", ATTEMPT_ID))
	}

	private fun parseMetrics(node: JsonNode): Map<String, Any> {
		exactFields(node, METRIC_FIELDS, "metrics")
		if (node.isEmpty) violation("metrics must contain at least one typed value")
		return node.properties().associate { (key, value) ->
			key to if (key == "coldStart") {
				if (!value.isBoolean) violation("metrics.coldStart must be boolean")
				value.booleanValue()
			} else {
				integerValue(value, "metrics.$key", 0, if (key.endsWith("BasisPoints")) 10_000 else Int.MAX_VALUE)
			}
		}
	}

	private fun exactFields(node: JsonNode, allowed: Set<String>, name: String) {
		if (!node.isObject) violation("$name must be an object")
		val unknown = node.propertyNames().filterNot(allowed::contains)
		if (unknown.isNotEmpty()) violation("$name contains unknown or private fields: ${unknown.joinToString()}")
	}

	private fun literal(node: JsonNode, field: String, expected: String) {
		if (text(node, field) != expected) violation("$field must be $expected")
	}

	private fun patternedField(node: JsonNode, field: String, pattern: Regex): String = text(node, field).also {
		if (!pattern.matches(it)) violation("$field has an invalid or non-opaque value")
	}

	private fun optionalPatterned(node: JsonNode, field: String, pattern: Regex): String? =
		node.optional(field).map { patternedValue(it, field, pattern) }.orElse(null)

	private fun patternedValue(node: JsonNode, name: String, pattern: Regex): String {
		if (!node.isString) violation("$name must be a string")
		return node.stringValue().also { if (!pattern.matches(it)) violation("$name has an invalid or non-opaque value") }
	}

	private fun text(node: JsonNode, field: String): String {
		val value = node.get(field) ?: violation("$field is required")
		if (!value.isString) violation("$field must be a string")
		return value.stringValue()
	}

	private fun timestamp(node: JsonNode, field: String): String = text(node, field).also {
		try { Instant.parse(it) } catch (_: DateTimeParseException) { violation("$field must be an RFC 3339 UTC timestamp") }
	}

	private fun array(node: JsonNode, field: String): List<JsonNode> {
		val value = node.get(field) ?: violation("$field is required")
		if (!value.isArray) violation("$field must be an array")
		return value.asArray().values().toList()
	}

	private fun stringList(node: JsonNode, field: String, pattern: Regex, nonEmpty: Boolean): List<String> {
		val values = array(node, field).mapIndexed { index, value -> patternedValue(value, "$field[$index]", pattern) }
		if (nonEmpty && values.isEmpty()) violation("$field must not be empty")
		if (values.distinct().size != values.size) violation("$field must contain unique values")
		return values
	}

	private fun integerValue(node: JsonNode, name: String, min: Int, max: Int): Int {
		if (!node.isIntegralNumber || !node.canConvertToInt()) violation("$name must be an integer")
		return node.intValue().also { if (it !in min..max) violation("$name must be from $min to $max") }
	}

	private inline fun <reified T : Enum<T>> enumValue(node: JsonNode, field: String): T = try {
		enumValueOf<T>(text(node, field))
	} catch (_: IllegalArgumentException) {
		violation("$field has an unsupported value")
	}

	private fun hash(node: JsonNode): String = sha256(canonical(node))

	private fun canonical(node: JsonNode): String = when {
		node.isObject -> node.propertyNames().sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
			mapper.writeValueAsString(key) + ":" + canonical(node.required(key))
		}
		node.isArray -> node.asArray().values().joinToString(separator = ",", prefix = "[", postfix = "]") { canonical(it) }
		node.isString -> mapper.writeValueAsString(node.stringValue())
		node.isNumber || node.isBoolean || node.isNull -> node.toString()
		else -> violation("artifact contains a non-JSON value")
	}

	private fun violation(message: String): Nothing = throw CertificationArtifactViolation(message)

	companion object {
		const val SCHEMA_ID = "https://plot.local/schemas/production-generation-certification-artifacts/v1"
		const val SCHEMA_VERSION = "plot.production-generation-certification/v1"

		private val ARTIFACT_ID = Regex("^artifact-[a-f0-9]{16,64}$")
		private val CAMPAIGN_ID = Regex("^campaign-[a-f0-9]{16,64}$")
		private val MODEL_EXECUTION_ID = Regex("^model-execution-[a-f0-9]{16,64}$")
		private val ATTEMPT_ID = Regex("^attempt-[a-f0-9]{16,64}$")
		private val HASH = Regex("^sha256:[a-f0-9]{64}$")
		private val REVISION = Regex("^(?:[a-f0-9]{40}|[a-f0-9]{64})$")
		private val REPORT_ID = Regex("^report-[a-f0-9]{16,64}$")
		private val SOURCE_ALIAS = Regex("^source-[a-f0-9]{16,64}$")
		private val PROCESS_ID = Regex("^process-[a-f0-9]{16,64}$")
		private val NAMESPACE = Regex("^namespace-[a-f0-9]{16,64}$")
		private val SCENARIO_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
		private val MODEL_ID = Regex("^[a-z0-9][a-z0-9._-]*/[a-zA-Z0-9][a-zA-Z0-9._:-]*$")
		private val PROVIDER_SLUG = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
		private val CODE = Regex("^[A-Z][A-Z0-9_]{1,63}$")

		private val CAMPAIGN_FIELDS = setOf("schemaVersion", "artifactType", "artifactId", "campaignId", "sealedAt", "sourceRevision", "corpusHash", "profileHash", "sourceSnapshotSetHash", "environmentFingerprint", "reportId", "approvedSourceAliases", "revisionLineage")
		private val EXECUTION_FIELDS = setOf("schemaVersion", "artifactType", "artifactId", "campaignId", "campaignManifestHash", "modelExecutionId", "sealedAt", "requestedModel", "servedModel", "modelProfileHash", "pinnedUpstream", "routePolicyHash", "processIdentity", "sourceNamespace", "idempotencyNamespace", "scenarioIds")
		private val EVIDENCE_FIELDS = setOf("schemaVersion", "artifactType", "artifactId", "campaignId", "campaignManifestHash", "modelExecutionId", "modelExecutionManifestHash", "recordedAt", "evidenceType", "subjectType", "attemptId", "scenarioId", "ordinal", "outcome", "metrics", "codes", "attribution", "lineage")
		private val REVISION_LINEAGE_FIELDS = setOf("relation", "priorCampaignId", "priorSourceRevision", "priorCampaignManifestHash")
		private val ATTRIBUTION_FIELDS = setOf("requestedModel", "servedModel", "observedUpstream", "responseIdHash")
		private val REPLACEMENT_FIELDS = setOf("relation", "priorArtifactId", "priorAttemptId")
		private val METRIC_FIELDS = setOf(
			"latencyMs", "promptTokens", "completionTokens", "reasoningTokens", "cachedTokens", "costUsdMicros",
			"rewriteCount", "citationCount", "reviewNeededSentenceCount", "unresolvedConflictCount", "modelCallCount",
			"citationPrecisionBasisPoints", "citationRecallBasisPoints", "supportedClaimRecallBasisPoints",
			"unsupportedClaimRecallBasisPoints", "conflictRecallBasisPoints", "notRequiredFalsePositiveBasisPoints",
			"exportEventCount", "listenerCount", "liveCredentialCount", "transientArtifactCount", "coldStart",
		)
	}
}
