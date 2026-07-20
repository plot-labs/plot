package com.plot.api.generation

import com.plot.api.ai.provider.ModelRole
import java.util.UUID

/**
 * A post-commit signal for infrastructure that needs to observe durable generation progress.
 * Implementations must not alter workflow state; production uses [NO_OP].
 */
fun interface GenerationCheckpointObserver {
	fun afterDurableCheckpoint(checkpoint: DurableGenerationCheckpoint)

	companion object {
		val NO_OP = GenerationCheckpointObserver { }
	}
}

data class DurableGenerationCheckpoint(
	val workspaceId: UUID,
	val runId: UUID,
	val invocationId: UUID,
	val role: ModelRole,
	val artifactType: String,
	val runStatus: GenerationRunStatus,
)
