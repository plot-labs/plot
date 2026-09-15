package com.plot.api.content

import com.plot.api.ai.prompt.ChangelogPrompt
import com.plot.api.ai.prompt.ChangelogPromptFactory
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
	): ChangelogPrompt
	fun reviewer(request: ReviewerModelRequest): ChangelogPrompt
	fun rewriter(request: RewriteModelRequest): ChangelogPrompt
}

@Component
class ContentTypeRegistry(
	private val changelogPromptFactory: ChangelogPromptFactory,
	private val launchAnnouncementPromptFactory: LaunchAnnouncementPromptFactory,
) {
	fun specFor(contentType: ContentType): ContentWriterSpec = when (contentType) {
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
			else -> changelogPromptFactory
		}

	companion object {
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
