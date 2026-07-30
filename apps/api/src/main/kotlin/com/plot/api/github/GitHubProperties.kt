package com.plot.api.github

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.github")
data class GitHubProperties(
	val enabled: Boolean = false,
	val devOnly: Boolean = true,
	val loopbackOnly: Boolean = true,
	val appId: String? = null,
	val appSlug: String? = null,
	val privateKey: String? = null,
	val stateSecret: String? = null,
	val apiBaseUrl: String = "https://api.github.com",
	val webBaseUrl: String = "https://github.com",
	val stateTtlSeconds: Long = 900,
	val importPageCap: Int = 20,
	val repositoryPageCap: Int = 100,
	val webhookSecret: String? = null,
	val releaseAutomationEnabled: Boolean = false,
	val releaseWorkerPollDelay: Duration = Duration.ofSeconds(5),
	val releaseWorkerLeaseTimeout: Duration = Duration.ofMinutes(2),
	val releaseWorkerMaxAttempts: Int = 5,
	val comparePageCap: Int = 10,
	val maxChangedFiles: Int = 300,
	val maxCommitPullRequestLookups: Int = 100,
	val maxDiffCharacters: Int = 120_000,
	val maxReleasePullRequests: Int = 200,
	val maxReleaseEvidenceBlocks: Int = 300,
	val maxReleaseTitleCharacters: Int = 500,
	val maxReleaseBodyCharacters: Int = 20_000,
	val maxReleaseEvidenceCharacters: Int = 120_000,
	val maxWebhookPayloadBytes: Int = 1_048_576,
) {
	init {
		require(!releaseWorkerPollDelay.isNegative && !releaseWorkerPollDelay.isZero) {
			"plot.github.release-worker-poll-delay must be positive"
		}
		require(!releaseWorkerLeaseTimeout.isNegative && !releaseWorkerLeaseTimeout.isZero) {
			"plot.github.release-worker-lease-timeout must be positive"
		}
		require(releaseWorkerMaxAttempts > 0) { "plot.github.release-worker-max-attempts must be positive" }
		require(maxReleasePullRequests > 0) { "plot.github.max-release-pull-requests must be positive" }
		require(maxReleaseEvidenceBlocks > 0) { "plot.github.max-release-evidence-blocks must be positive" }
		require(maxReleaseTitleCharacters > 0) { "plot.github.max-release-title-characters must be positive" }
		require(maxReleaseBodyCharacters > 0) { "plot.github.max-release-body-characters must be positive" }
		require(maxReleaseEvidenceCharacters > 0) { "plot.github.max-release-evidence-characters must be positive" }
		require(maxReleasePullRequests <= maxReleaseEvidenceBlocks) {
			"plot.github.max-release-pull-requests cannot exceed max-release-evidence-blocks"
		}
		require(maxReleaseTitleCharacters <= maxReleaseEvidenceCharacters) {
			"plot.github.max-release-title-characters cannot exceed max-release-evidence-characters"
		}
		require(maxReleaseBodyCharacters <= maxReleaseEvidenceCharacters) {
			"plot.github.max-release-body-characters cannot exceed max-release-evidence-characters"
		}
	}
}
