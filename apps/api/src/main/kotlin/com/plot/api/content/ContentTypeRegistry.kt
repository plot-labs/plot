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
	private val launchAnnouncementPromptFactory: LaunchAnnouncementPromptFactory,
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
		ContentType.LAUNCH_ANNOUNCEMENT -> ContentWriterSpec(
			contentType = ContentType.LAUNCH_ANNOUNCEMENT,
			promptVersion = LAUNCH_PROMPT_VERSION,
			outputSchemaVersion = LAUNCH_SCHEMA_VERSION,
			budgetVersion = BUDGET_VERSION,
			exportSlug = "launch-announcement",
		)
	}
	fun promptFactoryFor(promptVersion: String): ContentPromptFactory =
		when {
			isLaunchPromptVersion(promptVersion) -> launchAnnouncementPromptFactory
			else -> artifactPromptFactory
		}

	companion object {
		const val ARTIFACT_PROMPT_VERSION = "artifact-v1"
		const val ARTIFACT_SCHEMA_VERSION = "artifact-workflow-v5"
		const val CHANGELOG_PROMPT_VERSION = "changelog-v9"
		const val CHANGELOG_SCHEMA_VERSION = "artifact-workflow-v5"
		const val LAUNCH_PROMPT_VERSION = "launch-announcement-v3"
		const val LAUNCH_SCHEMA_VERSION = "launch-workflow-v1"
		const val BUDGET_VERSION = "budget-v1"
		const val LAUNCH_MAX_WRITER_SENTENCES = 4

		fun isLaunchPromptVersion(promptVersion: String): Boolean =
			promptVersion.startsWith("launch-announcement-")

		fun maxWriterSentences(promptVersion: String): Int? =
			if (isLaunchPromptVersion(promptVersion)) LAUNCH_MAX_WRITER_SENTENCES else null
	}
}
