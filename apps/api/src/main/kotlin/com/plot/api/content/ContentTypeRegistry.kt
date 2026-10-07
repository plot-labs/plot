package com.plot.api.content

import com.plot.api.ai.prompt.ArtifactPrompt
import com.plot.api.ai.prompt.ArtifactPromptFactory
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import org.springframework.stereotype.Component

data class ContentWriterSpec(
	val contentType: ContentType,
	val promptVersion: String,
	val outputSchemaVersion: String,
	val budgetVersion: String,
	val exportSlug: String,
)

interface ContentPromptFactory {
	fun writer(
		instruction: String?,
		evidence: List<EvidenceSnapshot>,
		style: FrozenContentContext? = null,
		documentVersion: Int = 1,
	): ArtifactPrompt
	fun reviewer(request: ReviewerModelRequest): ArtifactPrompt
	fun rewriter(request: RewriteModelRequest): ArtifactPrompt
}

@Component
class ContentTypeRegistry(
	private val artifactPromptFactory: ArtifactPromptFactory,
) {
	fun specFor(contentType: ContentType): ContentWriterSpec = when (contentType) {
		ContentType.ARTIFACT -> ContentWriterSpec(
			contentType = ContentType.ARTIFACT,
			promptVersion = ARTIFACT_PROMPT_VERSION,
			outputSchemaVersion = ARTIFACT_SCHEMA_VERSION,
			budgetVersion = BUDGET_VERSION,
			exportSlug = "artifact",
		)
		ContentType.CHANGELOG -> ContentWriterSpec(
			contentType = ContentType.CHANGELOG,
			promptVersion = CHANGELOG_PROMPT_VERSION,
			outputSchemaVersion = CHANGELOG_SCHEMA_VERSION,
			budgetVersion = BUDGET_VERSION,
			exportSlug = "changelog",
		)
	}
	/** Every frozen prompt version, including retired launch-announcement-* runs, uses the artifact prompts; content variety comes from skills. */
	@Suppress("UNUSED_PARAMETER")
	fun promptFactoryFor(promptVersion: String): ContentPromptFactory = artifactPromptFactory

	companion object {
		const val ARTIFACT_PROMPT_VERSION = "artifact-v1"
		const val ARTIFACT_SCHEMA_VERSION = "artifact-workflow-v5"
		const val CHANGELOG_PROMPT_VERSION = "changelog-v9"
		const val CHANGELOG_SCHEMA_VERSION = "artifact-workflow-v5"
		const val BUDGET_VERSION = "budget-v1"
	}
}
