package com.plot.api.content

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
) {
	fun isBlank(): Boolean =
		purpose.isNullOrBlank() &&
			audience.isNullOrBlank() &&
			availability.isNullOrBlank() &&
			pricing.isNullOrBlank() &&
			userAction.isNullOrBlank() &&
			confirmedFacts.isEmpty()

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
	}
}

data class ConfirmedFact(
	val body: String,
	val kind: String = "AVAILABILITY",
)

data class FrozenContentContext(
	val profile: ContentProfileRevision?,
	val brief: ContentBrief?,
)
