package com.plot.api.content

import java.net.URI
import java.time.Instant
import java.util.UUID

data class ContentProfileRevision(
	val id: UUID,
	val workspaceId: UUID,
	val revisionNumber: Int,
	val productSummary: String,
	val primaryAudience: String,
	val customerTerms: String,
	val tone: String,
	val defaultLocale: String,
	val bannedPhrases: List<String>,
	val createdByUserId: UUID,
	val createdAt: Instant,
)

data class ContentBrief(
	val purpose: String? = null,
	val audience: String? = null,
	val availability: String? = null,
	val pricing: String? = null,
	val userAction: String? = null,
	val confirmedFacts: List<ConfirmedFact> = emptyList(),
	val destinations: List<ContentBriefDestination> = emptyList(),
) {
	fun isBlank(): Boolean =
		purpose.isNullOrBlank() &&
			audience.isNullOrBlank() &&
			availability.isNullOrBlank() &&
			pricing.isNullOrBlank() &&
			userAction.isNullOrBlank() &&
			confirmedFacts.isEmpty() &&
				destinations.isEmpty()

	fun canonicalFingerprint(): String = buildString {
		append(purpose.orEmpty().trim()).append('|')
		append(audience.orEmpty().trim()).append('|')
		append(availability.orEmpty().trim()).append('|')
		append(pricing.orEmpty().trim()).append('|')
		append(userAction.orEmpty().trim()).append('|')
		confirmedFacts.forEach { fact ->
			append(fact.kind.trim()).append(':')
			append(fact.body.trim()).append(';')
		}
		destinations.forEach { destination ->
			append(destination.id).append(':')
			append(destination.label.trim()).append(':')
			append(destination.url.trim()).append(';')
		}
	}
}

data class ConfirmedFact(
	val body: String,
	val kind: String = "AVAILABILITY",
)

data class ContentBriefDestination(
	val id: UUID,
	val label: String,
	val url: String,
) {
	init {
		require(isSafeCtaLabel(label)) { "CTA destination label is invalid" }
		require(isAbsoluteHttpsUrl(url)) { "CTA destination URL must be an absolute HTTPS URL" }
	}
}

internal fun isSafeCtaLabel(value: String): Boolean = value.trim().let { label ->
	label.isNotBlank() && label.length <= 200 && label.none { it.isISOControl() }
}

internal fun isAbsoluteHttpsUrl(value: String): Boolean = try {
	val uri = URI(value.trim())
	uri.scheme?.lowercase() == "https" && !uri.isOpaque && !uri.host.isNullOrBlank() &&
		uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
		value.none { it.isISOControl() || it == '<' || it == '>' || it == '"' || it == '\'' }
} catch (_: IllegalArgumentException) {
	false
}

data class FrozenContentContext(
	val profile: ContentProfileRevision?,
	val brief: ContentBrief?,
)
