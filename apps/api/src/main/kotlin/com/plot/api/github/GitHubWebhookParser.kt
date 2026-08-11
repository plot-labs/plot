package com.plot.api.github

import com.plot.api.common.ApiException
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class ParsedGitHubWebhook(
	val externalDeliveryId: String,
	val eventType: String,
	val eventAction: String?,
	val installationId: Long?,
	val repositoryId: Long?,
	val repositoryIdsAdded: List<Long> = emptyList(),
	val repositoryIdsRemoved: List<Long> = emptyList(),
	val ref: String?,
	val beforeSha: String?,
	val afterSha: String?,
	val tagName: String?,
	val refCreated: Boolean?,
	val refDeleted: Boolean?,
	val forced: Boolean?,
	val commits: List<GitHubWebhookCommit> = emptyList(),
	val payloadHash: String,
)

data class GitHubWebhookCommit(
	val sha: String,
	val message: String,
	val author: String?,
	val timestamp: Instant?,
	val url: String?,
)

@Component
class GitHubWebhookParser(private val objectMapper: ObjectMapper) {
	fun parse(externalDeliveryId: String, eventType: String, rawBody: ByteArray): ParsedGitHubWebhook {
		val root = try {
			objectMapper.readTree(rawBody)
		} catch (_: Exception) {
			throw invalidPayload()
		}
		if (root == null || !root.isObject) throw invalidPayload()
		val ref = root.text("ref")
		return ParsedGitHubWebhook(
			externalDeliveryId = externalDeliveryId,
			eventType = eventType,
			eventAction = root.text("action"),
			installationId = root.path("installation").long("id"),
			repositoryId = root.path("repository").long("id"),
			repositoryIdsAdded = root.path("repositories_added").longIds(),
			repositoryIdsRemoved = root.path("repositories_removed").longIds(),
			ref = ref,
			beforeSha = root.text("before"),
			afterSha = root.text("after"),
			tagName = when {
				eventType == "release" && root.text("action") == "published" && root.path("release").isObject ->
					root.path("release").text("tag_name")
				eventType == "push" && ref?.startsWith(TAG_REF_PREFIX) == true -> ref.removePrefix(TAG_REF_PREFIX)
				else -> null
			},
			refCreated = root.boolean("created"),
			refDeleted = root.boolean("deleted"),
			forced = root.boolean("forced"),
			commits = root.path("commits").commits(),
			payloadHash = MessageDigest.getInstance("SHA-256").digest(rawBody).toHex(),
		)
	}

	private fun JsonNode.text(fieldName: String): String? = path(fieldName)
		.takeIf { it.isTextual }?.stringValue()?.takeIf { it.isNotBlank() }

	private fun JsonNode.long(fieldName: String): Long? = path(fieldName)
		.takeIf { it.canConvertToLong() }?.longValue()

	private fun JsonNode.boolean(fieldName: String): Boolean? = path(fieldName)
		.takeIf { it.isBoolean }?.booleanValue()

	private fun JsonNode.longIds(): List<Long> = if (isArray) {
		val ids = mutableListOf<Long>()
		forEach { node ->
			val id = node.path("id")
			if (id.canConvertToLong()) ids += id.longValue()
		}
		ids
	} else {
		emptyList()
	}

	private fun JsonNode.commits(): List<GitHubWebhookCommit> = if (isArray) {
		mapNotNull { node ->
			val sha = node.text("id") ?: return@mapNotNull null
			val message = node.text("message") ?: return@mapNotNull null
			GitHubWebhookCommit(
				sha = sha,
				message = message,
				author = node.path("author").text("username") ?: node.path("author").text("name"),
				timestamp = node.text("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() },
				url = node.text("url"),
			)
		}
	} else {
		emptyList()
	}

	private fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)

	private fun invalidPayload(): Nothing = throw ApiException(
		HttpStatus.BAD_REQUEST,
		"INVALID_GITHUB_WEBHOOK_PAYLOAD",
		"GitHub webhook payload is invalid",
	)

	private companion object {
		const val TAG_REF_PREFIX = "refs/tags/"
	}
}
