package com.plot.api.github

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class GitHubReleaseConventionAnalyzerTest {
	private val analyzer = GitHubReleaseConventionAnalyzer()

	@Test
	fun classifiesSupportedConventionsFromTheEntireSample() {
		val cases = listOf(
			GitHubReleaseTagSample(listOf("v1.2.3", "v2.0.0-rc.1"), GitHubReleaseSampleSource.RELEASES, false) to
				Pair(GitHubReleaseConvention.SEMVER_V, null),
			GitHubReleaseTagSample(listOf("1.2.3", "2.0.0+build.4"), GitHubReleaseSampleSource.TAGS, false) to
				Pair(GitHubReleaseConvention.SEMVER, null),
			GitHubReleaseTagSample(listOf("app-v1.2.3", "app-v1.3.0"), GitHubReleaseSampleSource.RELEASES, true) to
				Pair(GitHubReleaseConvention.PREFIXED, "app-"),
		)

		cases.forEach { (sample, expected) ->
			val result = analyzer.analyze(sample)

			assertEquals(expected.first, result.convention)
			assertEquals(expected.second, result.tagPrefix)
			assertEquals(sample.source, result.sampleSource)
			assertEquals(sample.tags.size, result.sampleSize)
			assertEquals(sample.truncated, result.sampleTruncated)
		}
	}

	@Test
	fun treatsNoTagsAndUnsupportedOrMixedTagsAsCompletedResults() {
		val noTags = analyzer.analyze(GitHubReleaseTagSample(emptyList(), null, false))
		assertEquals(GitHubReleaseConvention.NO_TAGS, noTags.convention)
		assertNull(noTags.sampleSource)
		assertEquals(0, noTags.sampleSize)

		listOf(
			listOf("v1.0.0", "release-candidate"),
			listOf("custom-only"),
			listOf("app-v1.0.0", "api-v1.1.0"),
		).forEach { tags ->
			assertEquals(
				GitHubReleaseConvention.MIXED,
				analyzer.analyze(GitHubReleaseTagSample(tags, GitHubReleaseSampleSource.TAGS, false)).convention,
			)
		}
	}

	@Test
	fun rejectsInvalidSemverInsteadOfPartiallyClassifyingIt() {
		listOf(
			"v01.2.3",
			"1.0",
			"v1.0.0-",
			"1.0.0+",
			"v1.0.0-01",
			"app-v1.0.0-alpha.01",
		).forEach { tag ->
			assertEquals(
				GitHubReleaseConvention.MIXED,
				analyzer.analyze(
					GitHubReleaseTagSample(listOf(tag), GitHubReleaseSampleSource.RELEASES, false),
				).convention,
			)
		}
	}
}
