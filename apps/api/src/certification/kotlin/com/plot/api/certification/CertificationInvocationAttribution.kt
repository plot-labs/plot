package com.plot.api.certification

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class CertificationInvocationAttributionAudit(
	val logicalCallIndex: Int,
	val role: String,
	val outcome: EvidenceOutcome,
	val code: CertificationFailureCode?,
	val gateway: String?,
	val requestedModel: String?,
	val servedModel: String?,
	val observedUpstream: String?,
	val promptTokens: Int?,
	val completionTokens: Int?,
	val reasoningTokens: Int?,
	val cachedTokens: Int?,
	val costUsdMicros: Long?,
	val latencyMs: Int?,
	val responseIdHash: String?,
	val upstreamIdHash: String?,
)

fun interface CertificationGenerationMetadataLookup {
	fun fetch(responseId: String): OpenRouterGenerationAttribution
}

/**
 * Resolves provider response IDs only in memory. The durable projection contains hashes and
 * allow-listed usage/route fields, never the raw response ID or provider response body.
 */
class CertificationInvocationAttributor(
	private val jdbcTemplate: JdbcTemplate,
	private val mapper: ObjectMapper,
	private val metadataLookup: CertificationGenerationMetadataLookup,
) {
	fun attribute(
		identity: CertificationAttemptIdentity,
		execution: ModelExecutionManifest,
	): List<CertificationInvocationAttributionAudit> {
		val runRoutes = jdbcTemplate.query(
			"select provider, model_name from generation_runs where workspace_id = ? and id = ? and idempotency_key = ?",
			{ rs, _ -> rs.getString(1) to rs.getString(2) },
			identity.workspaceId, identity.runId, identity.idempotencyKey,
		)
		if (runRoutes.size != 1 || runRoutes.single() != (EXPECTED_GATEWAY_DB to execution.requestedModel)) {
			return routeFailureRows(identity, EvidenceOutcome.HARD_GATE_FAIL, CertificationFailureCode.ATTRIBUTION_MISMATCH)
		}
		val rows = invocationRows(identity)
		return rows.map { row -> attribute(row, execution) }
	}

	private fun attribute(row: InvocationRouteRow, execution: ModelExecutionManifest): CertificationInvocationAttributionAudit {
		val metadata = parseMetadata(row.resultMetadata)
		if (row.status != "SUCCEEDED" || row.provider != EXPECTED_GATEWAY_DB || row.modelName != execution.requestedModel ||
			row.providerRequestId.isNullOrBlank() || metadata == null ||
			metadata.propertyNames().toSet() != RESULT_METADATA_FIELDS ||
			metadata.text("gateway") != EXPECTED_GATEWAY || metadata.text("requestedModel") != execution.requestedModel ||
			metadata.text("servedModel") != execution.servedModel || metadata.text("responseId") != row.providerRequestId ||
			metadata.text("finishReason").isNullOrBlank()
		) return failure(row, EvidenceOutcome.HARD_GATE_FAIL, CertificationFailureCode.ATTRIBUTION_MISMATCH)
		return try {
			val attribution = metadataLookup.fetch(row.providerRequestId)
			if (attribution.servedModel != execution.servedModel || attribution.providerSlug != execution.pinnedUpstream ||
				attribution.responseIdHash != safeSha256("openrouter-response", row.providerRequestId)
			) {
				failure(row, EvidenceOutcome.HARD_GATE_FAIL, CertificationFailureCode.ATTRIBUTION_MISMATCH)
			} else {
				CertificationInvocationAttributionAudit(
					logicalCallIndex = row.logicalCallIndex,
					role = row.role,
					outcome = EvidenceOutcome.PASS,
					code = null,
					gateway = EXPECTED_GATEWAY,
					requestedModel = execution.requestedModel,
					servedModel = attribution.servedModel,
					observedUpstream = attribution.providerSlug,
					promptTokens = attribution.nativeTokens.prompt,
					completionTokens = attribution.nativeTokens.completion,
					reasoningTokens = attribution.nativeTokens.reasoning,
					cachedTokens = attribution.nativeTokens.cached,
					costUsdMicros = attribution.costUsdMicros,
					latencyMs = attribution.latencyMs,
					responseIdHash = attribution.responseIdHash,
					upstreamIdHash = attribution.upstreamIdHash,
				)
			}
		} catch (exception: CertificationPreflightException) {
			failure(
				row,
				if (exception.disposition == CertificationDisposition.INCONCLUSIVE) EvidenceOutcome.INCONCLUSIVE else EvidenceOutcome.HARD_GATE_FAIL,
				exception.code,
			)
		} catch (_: RuntimeException) {
			// Unknown provider/client failures are collapsed to an allow-listed hard failure.
			// Exception messages and response bodies never cross into the audit artifact.
			failure(row, EvidenceOutcome.HARD_GATE_FAIL, CertificationFailureCode.METADATA_INVALID)
		}
	}

	private fun routeFailureRows(
		identity: CertificationAttemptIdentity,
		outcome: EvidenceOutcome,
		code: CertificationFailureCode,
	): List<CertificationInvocationAttributionAudit> = invocationRows(identity).map { failure(it, outcome, code) }

	private fun invocationRows(identity: CertificationAttemptIdentity): List<InvocationRouteRow> = jdbcTemplate.query(
		"""
		select role, logical_call_index, status, provider, model_name, provider_request_id, result_metadata::text
		from model_invocations where workspace_id = ? and generation_run_id = ? order by logical_call_index
		""".trimIndent(),
		{ rs, _ -> InvocationRouteRow(
			rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
		) },
		identity.workspaceId, identity.runId,
	)

	private fun parseMetadata(value: String?): JsonNode? = value?.let {
		runCatching { mapper.readTree(it) }.getOrNull()?.takeIf(JsonNode::isObject)
	}

	private fun failure(
		row: InvocationRouteRow,
		outcome: EvidenceOutcome,
		code: CertificationFailureCode,
	) = CertificationInvocationAttributionAudit(
		row.logicalCallIndex, row.role, outcome, code, null, null, null, null,
		null, null, null, null, null, null, null, null,
	)

	private fun JsonNode.text(name: String): String? = get(name)?.takeIf(JsonNode::isString)?.stringValue()

	private data class InvocationRouteRow(
		val role: String,
		val logicalCallIndex: Int,
		val status: String,
		val provider: String,
		val modelName: String,
		val providerRequestId: String?,
		val resultMetadata: String?,
	)

	companion object {
		private const val EXPECTED_GATEWAY = "openrouter"
		private const val EXPECTED_GATEWAY_DB = "OPENROUTER"
		private val RESULT_METADATA_FIELDS = setOf("gateway", "requestedModel", "servedModel", "responseId", "finishReason")
	}
}
