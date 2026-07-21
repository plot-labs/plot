package com.plot.api.generation

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.config.PlotAiProperties
import com.plot.api.dev.DevContext
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.writingblock.WritingBlockRepository
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import com.plot.api.generation.dto.GenerationRunTimingResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class GenerationRunService(
	private val devContext: DevContext,
	private val writingBlockRepository: WritingBlockRepository,
	private val sourceManagedAccessGuard: SourceManagedAccessGuard,
	private val evidenceSnapshotService: EvidenceSnapshotService,
	private val workflowService: GenerationWorkflowService,
	private val persistence: GenerationPersistence,
	private val dispatcher: GenerationRunDispatcher,
	private val uuidGenerator: UuidGenerator,
	private val properties: PlotAiProperties,
	private val objectMapper: ObjectMapper,
) {
	fun create(
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String?,
		idempotencyKey: String,
	): GenerationWorkflowState {
		require(idempotencyKey.isNotBlank()) { "Idempotency key is required" }
		require(writingBlockIds.isNotEmpty()) { "At least one Writing Block is required" }
		require(writingBlockIds.distinct().size == writingBlockIds.size) { "Writing Block IDs must be unique" }
		val normalizedKey = idempotencyKey.trim()
		val requestFingerprint = fingerprint(sourceScopeId, writingBlockIds, instruction)
		persistence.findIdempotentRun(
			devContext.devWorkspaceId, devContext.devUserId, normalizedKey, requestFingerprint,
		)?.let { return it }
		val selected = writingBlockRepository.findSelectedReadable(
			devContext.devWorkspaceId,
			sourceScopeId,
			writingBlockIds,
		).associateBy { it.id }
		if (selected.size != writingBlockIds.size) throw GenerationSourceAccessException()
		if (selected.values.any { it.sourceNamespaceId != null }) sourceManagedAccessGuard.requireReadable()
		val evidenceCharacters = selected.values.sumOf { it.title.orEmpty().length + it.body.orEmpty().length }
		require(evidenceCharacters <= properties.maxEvidenceCharacters) {
			"Selected evidence exceeds the ${properties.maxEvidenceCharacters} character limit"
		}
		val runId = uuidGenerator.next()
		val evidence = writingBlockIds.mapIndexed { index, id ->
			evidenceSnapshotService.snapshot(runId, index, selected.getValue(id))
		}
		val initialState = workflowService.start(runId, evidence, instruction)
		val state = persistence.createRun(GenerationRunReservation(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			sourceScopeId = sourceScopeId,
			idempotencyKey = normalizedKey,
			requestFingerprint = requestFingerprint,
			state = initialState,
			provider = properties.provider.uppercase(),
			modelName = properties.model?.trim().takeUnless { it.isNullOrBlank() } ?: "not-configured",
			budgetJson = objectMapper.writeValueAsString(mapOf(
				"maxModelCalls" to properties.maxModelCalls,
				"maxTotalTokens" to properties.maxTotalTokens,
				"maxRunDurationMillis" to properties.maxRunDuration.toMillis(),
			)),
		))
		dispatcher.dispatch()
		return state
	}

	fun get(runId: UUID): GenerationWorkflowState = try {
		persistence.loadState(devContext.devWorkspaceId, runId)
	} catch (_: GenerationRunNotFoundException) {
		throw ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND", "Generation run not found")
	}

	fun getTiming(runId: UUID): GenerationRunTimingResponse = try {
		persistence.loadTiming(devContext.devWorkspaceId, runId)
	} catch (_: GenerationRunNotFoundException) {
		throw ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND", "Generation run not found")
	}

	private fun fingerprint(sourceScopeId: UUID, ids: List<UUID>, instruction: String?): String {
		val canonical = buildString {
			append(sourceScopeId).append('\n')
			ids.forEach { append(it).append('\n') }
			append(instruction?.trim().orEmpty())
		}
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
	}
}

class GenerationSourceAccessException : IllegalArgumentException("One or more Writing Blocks are unavailable in the source scope")
