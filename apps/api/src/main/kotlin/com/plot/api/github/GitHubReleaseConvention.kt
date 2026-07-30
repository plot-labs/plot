package com.plot.api.github

enum class GitHubReleaseConvention {
	SEMVER_V,
	SEMVER,
	PREFIXED,
	MIXED,
	NO_TAGS,
}

enum class GitHubReleaseSampleSource {
	RELEASES,
	TAGS,
}

data class GitHubReleaseTagSample(
	val tags: List<String>,
	val source: GitHubReleaseSampleSource?,
	val truncated: Boolean,
)

data class GitHubReleaseConventionAnalysis(
	val convention: GitHubReleaseConvention,
	val tagPrefix: String?,
	val sampleSource: GitHubReleaseSampleSource?,
	val sampleSize: Int,
	val sampleTruncated: Boolean,
)

data class GitHubTagPage(
	val tags: List<String>,
	val truncated: Boolean,
)

class GitHubReleaseConventionAnalyzer {
	fun analyze(sample: GitHubReleaseTagSample): GitHubReleaseConventionAnalysis {
		val tags = sample.tags.distinct()
		if (tags.isEmpty()) {
			return GitHubReleaseConventionAnalysis(
				convention = GitHubReleaseConvention.NO_TAGS,
				tagPrefix = null,
				sampleSource = null,
				sampleSize = 0,
				sampleTruncated = sample.truncated,
			)
		}

		val convention: GitHubReleaseConvention
		val prefix: String?
		when {
			tags.all { SEMVER_V.matches(it) && hasValidPrerelease(it.drop(1)) } -> {
				convention = GitHubReleaseConvention.SEMVER_V
				prefix = null
			}
			tags.all { SEMVER.matches(it) && hasValidPrerelease(it) } -> {
				convention = GitHubReleaseConvention.SEMVER
				prefix = null
			}
			else -> {
				val prefixes = tags.mapNotNull(::prefixedSemverPrefix)
				.distinct()
				.takeIf { it.size == 1 && it.single().isNotBlank() }
				?.single()
				?.takeIf { candidate -> tags.all { prefixedSemverPrefix(it) == candidate } }
				convention = if (prefixes == null) GitHubReleaseConvention.MIXED else GitHubReleaseConvention.PREFIXED
				prefix = prefixes
			}
		}
		return GitHubReleaseConventionAnalysis(
			convention = convention,
			tagPrefix = prefix,
			sampleSource = sample.source,
			sampleSize = tags.size,
			sampleTruncated = sample.truncated,
		)
	}

	private fun prefixedSemverPrefix(tag: String): String? {
		val match = PREFIXED_SEMVER.matchEntire(tag) ?: return null
		return match.groupValues[1].takeIf {
			hasValidPrerelease(match.groupValues[2].removePrefix("v"))
		}
	}

	private fun hasValidPrerelease(semver: String): Boolean {
		val prerelease = semver.substringBefore('+').substringAfter('-', "")
		return prerelease.isEmpty() || prerelease.split('.').none {
			it.length > 1 && it.startsWith('0') && it.all(Char::isDigit)
		}
	}

	private companion object {
		const val CORE =
			"""(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"""
		val SEMVER = Regex("^$CORE$")
		val SEMVER_V = Regex("^v$CORE$")
		val PREFIXED_SEMVER = Regex("^(.+[-/_@])(v?$CORE)$")
	}
}
