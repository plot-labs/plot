package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.source.ImportedWritingBlock
import com.plot.api.writingblock.WritingBlockImportService
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

data class GitHubReleaseEvidence(
	val observationId: UUID,
	val writingBlockIds: List<UUID>,
)

interface GitHubReleaseEvidenceService {
	fun collect(
		principal: WorkspacePrincipal,
		context: GitHubReleaseSourceContext,
		request: GitHubReleaseDraftRequest,
		range: GitHubReleaseRange,
	): GitHubReleaseEvidence
}

@Service
class DefaultGitHubReleaseEvidenceService(
	private val client: GitHubClient,
	private val transformer: GitHubWritingBlockTransformer,
	private val writingBlockImportService: WritingBlockImportService,
	private val jdbcTemplate: JdbcTemplate,
	private val uuidGenerator: UuidGenerator,
	private val properties: GitHubProperties,
	private val transactionTemplate: TransactionTemplate,
) : GitHubReleaseEvidenceService {
	override fun collect(
		principal: WorkspacePrincipal,
		context: GitHubReleaseSourceContext,
		request: GitHubReleaseDraftRequest,
		range: GitHubReleaseRange,
	): GitHubReleaseEvidence {
		require(principal.workspaceId == context.workspaceId) { "Release evidence workspace does not match source context" }
		require(request.workspaceId == context.workspaceId) { "Release request workspace does not match source context" }
		require(request.sourceScopeId == context.sourceScopeId) { "Release request source scope does not match source context" }
		require(range.baseSha.isNotBlank() && range.headSha.isNotBlank()) { "Release range requires exact boundaries" }

		val observationId = checkNotNull(transactionTemplate.execute {
			reserveObservation(principal.workspaceId, context, request, range)
		})
		try {
			val lookedUpCommits = range.comparison.commits
				.take(properties.maxCommitPullRequestLookups.coerceAtLeast(0))
			val pullRequestsById = linkedMapOf<Long, GitHubPullRequest>()
			val representedCommitShas = linkedSetOf<String>()
			var retainedPullRequestCharacters = 0L
			lookedUpCommits.forEach { commit ->
				val responsePullRequests = client.listPullRequestsForCommit(
					context.installationId,
					context.repositoryId,
					context.owner,
					context.repository,
					commit.sha,
				).filter { pullRequest ->
					pullRequest.mergedAt != null &&
						pullRequest.baseBranch == context.defaultBranch
				}
				if (responsePullRequests.isNotEmpty()) representedCommitShas += commit.sha
				responsePullRequests.forEach pullRequestLoop@{ pullRequest ->
					if (pullRequestsById.containsKey(pullRequest.id)) return@pullRequestLoop
					validateRawPullRequest(pullRequest)
					if (pullRequestsById.size + 1 > properties.maxReleasePullRequests) evidenceTooLarge()
					retainedPullRequestCharacters +=
						pullRequest.title.length.toLong() + pullRequest.body.orEmpty().length
					if (retainedPullRequestCharacters > properties.maxReleaseEvidenceCharacters) evidenceTooLarge()
					pullRequestsById[pullRequest.id] = pullRequest
				}
			}
			val sortedPullRequests = pullRequestsById.values
				.sortedWith(compareBy<GitHubPullRequest> { it.number }.thenBy { it.id })
			val commits = range.comparison.commits
				.filterNot { it.sha in representedCommitShas }
				.distinctBy { it.sha }
				.mapNotNull(::commitContent)
			validateRequiredEvidence(sortedPullRequests, commits)

			val releaseMetadata = releaseMetadata(request, range)
			val releaseObjectPrefix = releaseObjectPrefix(request, range)
			val blocks = buildList {
				sortedPullRequests.forEach { pullRequest ->
					val transformed = transformer.transform(
						context.sourceNamespaceId,
						context.sourceScopeId,
						observationId,
						pullRequest,
					)
					add(transformed.copy(
						externalObjectKey = "$releaseObjectPrefix:pull_request:${pullRequest.id}",
						metadata = transformed.metadata + releaseMetadata + mapOf("pullRequestId" to pullRequest.id),
					))
				}
				commits.forEach { commit ->
					add(commitBlock(context, observationId, commit, releaseObjectPrefix, releaseMetadata))
				}
			}.toMutableList()
			if (blocks.isNotEmpty()) {
				val first = blocks.first()
				val changedFiles = changedFileMetadata(range.comparison)
				val summaryBudget = diffSummaryBudget(blocks)
				val changedFileSummary = buildChangedFileDiffSummary(
					range.comparison.files,
					properties.maxChangedFiles,
					summaryBudget,
				)
				blocks.indices.forEach { index ->
					blocks[index] = blocks[index].copy(
						metadata = blocks[index].metadata +
							mapOf("changedFileDiffTruncated" to changedFileSummary.truncated),
					)
				}
				blocks[0] = first.copy(
					body = if (changedFileSummary.text.isBlank()) {
						first.body
					} else {
						appendSummary(first.body, changedFileSummary.text)
					},
					metadata = blocks[0].metadata + mapOf("changedFiles" to changedFiles),
				)
			}
			validateConstructedEvidence(blocks)

			val blockIds = checkNotNull(transactionTemplate.execute {
				val now = Instant.now()
				val ids = blocks.map { block ->
					writingBlockImportService.upsert(principal, block, now).blockId
				}
				completeObservation(principal.workspaceId, context.sourceScopeId, observationId, "COMPLETED")
				ids
			})
			return GitHubReleaseEvidence(observationId, blockIds)
		} catch (exception: Exception) {
			transactionTemplate.executeWithoutResult {
				completeObservation(principal.workspaceId, context.sourceScopeId, observationId, "FAILED")
			}
			throw exception
		}
	}

	private fun reserveObservation(
		workspaceId: UUID,
		context: GitHubReleaseSourceContext,
		request: GitHubReleaseDraftRequest,
		range: GitHubReleaseRange,
	): UUID {
		val coverageKey = "release:${request.tagName}:${range.baseSha}...${range.headSha}"
		jdbcTemplate.query(
			"select pg_advisory_xact_lock(hashtextextended(?, 0))",
			{ _, _ -> Unit },
			"$workspaceId:GITHUB_RELEASE:$coverageKey",
		)
		val generation = jdbcTemplate.queryForObject(
			"""
			select coalesce(max(generation), -1) + 1
			from source_observations
			where workspace_id = ? and authority_owner = 'GITHUB_RELEASE' and coverage_key = ?
			""".trimIndent(),
			Long::class.java,
			workspaceId,
			coverageKey,
		) ?: 0L
		val observationId = uuidGenerator.next()
		val now = Instant.now()
		jdbcTemplate.update(
			"""
			insert into source_observations (
			 id, workspace_id, source_scope_id, binding_id, authority_owner, coverage_key,
			 observation_mode, generation, status, started_at, created_at
			) values (?, ?, ?, ?, 'GITHUB_RELEASE', ?, 'PARTIAL', ?, 'RUNNING', ?, ?)
			""".trimIndent(),
			observationId,
			workspaceId,
			context.sourceScopeId,
			context.bindingId,
			coverageKey,
			generation,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return observationId
	}

	private fun completeObservation(
		workspaceId: UUID,
		sourceScopeId: UUID,
		observationId: UUID,
		status: String,
	) {
		val completed = jdbcTemplate.update(
			"""
			update source_observations
			set status = ?, completed_at = ?
			where workspace_id = ? and id = ? and source_scope_id = ? and status = 'RUNNING'
			""".trimIndent(),
			status,
			Timestamp.from(Instant.now()),
			workspaceId,
			observationId,
			sourceScopeId,
		)
		check(completed == 1) { "Release evidence observation is no longer running" }
	}

	private fun releaseMetadata(
		request: GitHubReleaseDraftRequest,
		range: GitHubReleaseRange,
	): Map<String, Any?> {
		return mapOf(
			"releaseTag" to request.tagName,
			"baseSha" to range.baseSha,
			"headSha" to range.headSha,
			"boundaryReason" to range.boundaryReason,
			"changedFileCount" to range.comparison.files
				.take(properties.maxChangedFiles.coerceAtLeast(0))
				.size,
			"changedFilesTruncated" to (
				range.comparison.filesTruncated ||
					range.comparison.files.size > properties.maxChangedFiles.coerceAtLeast(0)
				),
		)
	}

	private fun changedFileMetadata(comparison: GitHubCompareResult): List<Map<String, Any?>> =
		comparison.files
			.take(properties.maxChangedFiles.coerceAtLeast(0))
			.map { file ->
				mapOf(
					"filename" to file.filename.take(MAX_FILE_NAME_CHARACTERS),
					"previousFilename" to file.previousFilename?.take(MAX_FILE_NAME_CHARACTERS),
					"status" to file.status,
					"additions" to file.additions,
					"deletions" to file.deletions,
				)
			}

	private fun releaseObjectPrefix(request: GitHubReleaseDraftRequest, range: GitHubReleaseRange): String =
		"release:${request.tagName}:${range.baseSha}...${range.headSha}"

	private fun commitBlock(
		context: GitHubReleaseSourceContext,
		observationId: UUID,
		content: CommitContent,
		releaseObjectPrefix: String,
		releaseMetadata: Map<String, Any?>,
	): ImportedWritingBlock {
		val commit = content.commit
		return ImportedWritingBlock(
			sourceNamespaceId = context.sourceNamespaceId,
			sourceScopeId = context.sourceScopeId,
			observationId = observationId,
			externalObjectKey = "$releaseObjectPrefix:commit:${commit.sha}",
			sourceOrigin = "integration",
			sourceKind = "commit",
			title = content.title,
			body = content.body,
			url = commit.url,
			canonicalUrl = commit.url,
			author = commit.author,
			platform = "github",
			metadata = releaseMetadata + mapOf("sha" to commit.sha),
			sourceCreatedAt = content.committedAt,
			sourceUpdatedAt = content.committedAt,
		)
	}

	private fun commitContent(commit: GitHubCommit): CommitContent? {
		if (
			commit.message.length.toLong() >
			properties.maxReleaseTitleCharacters.toLong() + properties.maxReleaseBodyCharacters + 1L
		) {
			evidenceTooLarge()
		}
		val message = commit.message.trim()
		if (message.isBlank()) return null
		val title = message.lineSequence().first().trim().takeIf { it.isNotBlank() } ?: return null
		val body = message.lineSequence().drop(1).joinToString("\n").trim().takeIf { it.isNotBlank() }
		return CommitContent(commit, title, body, commit.committedAt ?: Instant.EPOCH)
	}

	private fun validateRawPullRequest(pullRequest: GitHubPullRequest) {
		if (
			pullRequest.title.length > properties.maxReleaseTitleCharacters ||
			pullRequest.body.orEmpty().length > properties.maxReleaseBodyCharacters
		) {
			evidenceTooLarge()
		}
	}

	private fun validateRequiredEvidence(
		pullRequests: List<GitHubPullRequest>,
		commits: List<CommitContent>,
	) {
		if (pullRequests.size + commits.size > properties.maxReleaseEvidenceBlocks) evidenceTooLarge()
		var totalCharacters = 0L
		pullRequests.forEach { pullRequest ->
			val title = pullRequest.title.trim()
			val body = pullRequest.body?.trim().orEmpty()
			validateBlockCharacters(title, body)
			totalCharacters += title.length.toLong() + body.length
			if (totalCharacters > properties.maxReleaseEvidenceCharacters) evidenceTooLarge()
		}
		commits.forEach { content ->
			validateBlockCharacters(content.title, content.body.orEmpty())
			totalCharacters += content.title.length.toLong() + content.body.orEmpty().length
			if (totalCharacters > properties.maxReleaseEvidenceCharacters) evidenceTooLarge()
		}
	}

	private fun validateConstructedEvidence(blocks: List<ImportedWritingBlock>) {
		if (blocks.size > properties.maxReleaseEvidenceBlocks) evidenceTooLarge()
		var totalCharacters = 0L
		blocks.forEach { block ->
			validateBlockCharacters(block.title, block.body.orEmpty())
			totalCharacters += block.title.length.toLong() + block.body.orEmpty().length
			if (totalCharacters > properties.maxReleaseEvidenceCharacters) evidenceTooLarge()
		}
	}

	private fun validateBlockCharacters(title: String, body: String) {
		if (
			title.length > properties.maxReleaseTitleCharacters ||
			body.length > properties.maxReleaseBodyCharacters
		) {
			evidenceTooLarge()
		}
	}

	private fun diffSummaryBudget(blocks: List<ImportedWritingBlock>): Int {
		if (blocks.isEmpty()) return 0
		val requiredCharacters = blocks.sumOf { it.title.length.toLong() + it.body.orEmpty().length }
		val firstBodyLength = blocks.first().body.orEmpty().length
		val envelopeCharacters = CHANGED_FILES_HEADING.length +
			if (firstBodyLength == 0) 0 else BLOCK_SUMMARY_SEPARATOR.length
		val totalRemaining = properties.maxReleaseEvidenceCharacters.toLong() -
			requiredCharacters -
			envelopeCharacters
		val bodyRemaining = properties.maxReleaseBodyCharacters.toLong() -
			firstBodyLength -
			envelopeCharacters
		return minOf(
			properties.maxDiffCharacters.toLong(),
			totalRemaining,
			bodyRemaining,
		).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
	}

	private fun appendSummary(body: String?, summary: String): String =
		listOfNotNull(
			body?.trim()?.takeIf { it.isNotBlank() },
			"$CHANGED_FILES_HEADING$summary",
		).joinToString(BLOCK_SUMMARY_SEPARATOR)

	private fun evidenceTooLarge(): Nothing = throw ApiException(
		HttpStatus.CONTENT_TOO_LARGE,
		"GITHUB_RELEASE_EVIDENCE_TOO_LARGE",
		"GitHub release evidence exceeds configured limits",
	)

	private companion object {
		const val CHANGED_FILES_HEADING = "Changed files:\n"
		const val BLOCK_SUMMARY_SEPARATOR = "\n\n"
	}
}

private data class CommitContent(
	val commit: GitHubCommit,
	val title: String,
	val body: String?,
	val committedAt: Instant,
)

internal data class ChangedFileDiffSummary(
	val text: String,
	val truncated: Boolean,
)

internal const val CHANGED_FILE_DIFF_TRUNCATED_MARKER = "[Diff summary truncated]"
private const val MAX_FILE_NAME_CHARACTERS = 512

internal fun buildChangedFileDiffSummary(
	files: List<GitHubChangedFile>,
	maxChangedFiles: Int,
	characterLimit: Int,
): ChangedFileDiffSummary {
	if (files.isEmpty()) return ChangedFileDiffSummary("", truncated = false)
	if (characterLimit <= 0) return ChangedFileDiffSummary("", truncated = true)
	val boundedFiles = files.take(maxChangedFiles.coerceAtLeast(0))
	val acceptedEntries = mutableListOf<String>()
	var firstRejectedHeader: String? = null
	var truncated = files.size > boundedFiles.size
	for (file in boundedFiles) {
		val header = buildString {
			append(file.status)
				.append(' ')
				.append(file.filename.take(MAX_FILE_NAME_CHARACTERS))
				.append(" (+")
				.append(file.additions)
				.append(" -")
				.append(file.deletions)
				.append(')')
		}
		val rawPatch = file.patch.orEmpty()
		val patchCaptureLimit = (characterLimit.toLong() + 1L)
			.coerceAtMost(Int.MAX_VALUE.toLong())
			.toInt()
		val patchWasCapped = rawPatch.length > patchCaptureLimit
		val patch = rawPatch.take(patchCaptureLimit).trim()
		val entry = if (patch.isBlank()) {
			header
		} else {
			"$header\n${patch.take(characterLimit + 1)}"
		}
		val candidate = (acceptedEntries + entry).joinToString("\n")
		if (candidate.length <= characterLimit && !patchWasCapped) {
			acceptedEntries += entry
		} else {
			truncated = true
			firstRejectedHeader = header
			break
		}
	}
	if (!truncated) {
		return ChangedFileDiffSummary(acceptedEntries.joinToString("\n"), truncated = false)
	}
	val marker = CHANGED_FILE_DIFF_TRUNCATED_MARKER
	while (acceptedEntries.isNotEmpty()) {
		val withHeaderAndMarker = firstRejectedHeader?.let {
			(acceptedEntries + it + marker).joinToString("\n")
		}
		if (withHeaderAndMarker != null && withHeaderAndMarker.length <= characterLimit) {
			return ChangedFileDiffSummary(withHeaderAndMarker, truncated = true)
		}
		val withMarker = (acceptedEntries + marker).joinToString("\n")
		if (withMarker.length <= characterLimit) {
			return ChangedFileDiffSummary(withMarker, truncated = true)
		}
		acceptedEntries.removeLast()
	}
	val headerWithMarker = firstRejectedHeader?.let { "$it\n$marker" }
	if (headerWithMarker != null && headerWithMarker.length <= characterLimit) {
		return ChangedFileDiffSummary(headerWithMarker, truncated = true)
	}
	return ChangedFileDiffSummary(
		text = marker.takeIf { it.length <= characterLimit }.orEmpty(),
		truncated = true,
	)
}
