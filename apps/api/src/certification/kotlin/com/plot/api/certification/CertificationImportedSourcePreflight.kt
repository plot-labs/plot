package com.plot.api.certification

import com.plot.api.dev.DevContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

data class ImportedSourcePreflightResult(
	val schemaVersion: String = "certification-imported-source-preflight-v1",
	val sourceAlias: String,
	val writingBlockCount: Int,
	val sourceSnapshotSetHash: String,
	val eligible: Boolean,
	val codes: List<SensitiveSourceCode>,
)

data class CertificationSourceApproval(
	val schemaVersion: String,
	val approvalId: String,
	val approvedByOwnerAlias: String,
	val approvedAt: Instant,
	val sourceAlias: String,
	val sourceWindowStart: Instant,
	val sourceWindowEnd: Instant,
	val approvedOriginalUrls: List<String>,
	val approvedModelVisibleFields: List<String>,
)

object CertificationSourceApprovalContract {
	private val fields = setOf(
		"schemaVersion", "approvalId", "approvedByOwnerAlias", "approvedAt", "sourceAlias",
		"sourceWindowStart", "sourceWindowEnd", "approvedOriginalUrls", "approvedModelVisibleFields",
	)
	private val approvalId = Regex("^approval-[a-f0-9]{16,64}$")
	private val ownerAlias = Regex("^owner-[a-f0-9]{16,64}$")
	private val sourceAlias = Regex("^source-[a-f0-9]{16,64}$")

	fun read(node: JsonNode): CertificationSourceApproval {
		if (!node.isObject || node.propertyNames().toSet() != fields ||
			node.get("schemaVersion")?.stringValue() != "certification-source-approval-v1"
		) reject()
		fun text(name: String) = node.get(name)?.takeIf(JsonNode::isString)?.stringValue() ?: reject()
		fun timestamp(name: String) = runCatching { Instant.parse(text(name)) }.getOrElse { reject() }
		val urls = node.get("approvedOriginalUrls")?.takeIf(JsonNode::isArray)?.asArray()?.values()?.map {
			it.takeIf(JsonNode::isString)?.stringValue()?.takeIf(::canonicalGitHubUrl) ?: reject()
		} ?: reject()
		val visibleFields = node.get("approvedModelVisibleFields")?.takeIf(JsonNode::isArray)?.asArray()?.values()?.map {
			it.takeIf(JsonNode::isString)?.stringValue() ?: reject()
		} ?: reject()
		val approval = CertificationSourceApproval(
			schemaVersion = text("schemaVersion"), approvalId = text("approvalId"),
			approvedByOwnerAlias = text("approvedByOwnerAlias"), approvedAt = timestamp("approvedAt"),
			sourceAlias = text("sourceAlias"), sourceWindowStart = timestamp("sourceWindowStart"),
			sourceWindowEnd = timestamp("sourceWindowEnd"), approvedOriginalUrls = urls,
			approvedModelVisibleFields = visibleFields,
		)
		if (!approvalId.matches(approval.approvalId) || !ownerAlias.matches(approval.approvedByOwnerAlias) ||
			!sourceAlias.matches(approval.sourceAlias) || !approval.sourceWindowStart.isBefore(approval.sourceWindowEnd) ||
			urls.isEmpty() || urls.distinct().size != urls.size ||
			visibleFields != MODEL_VISIBLE_FIELDS
		) reject()
		return approval
	}

	val MODEL_VISIBLE_FIELDS = listOf(
		"sourceProvider", "sourceKind", "sourceLabel", "title", "body", "excerpt", "sourceCreatedAt", "sourceUpdatedAt",
	)

	private fun reject(): Nothing = throw CertificationImportedSourcePreflightException()
}

class CertificationImportedSourcePreflight(
	private val jdbcTemplate: JdbcTemplate,
	private val devContext: DevContext = DevContext(),
) {
	fun inspect(approval: CertificationSourceApproval, writingBlockIds: List<UUID>): ImportedSourcePreflightResult {
		val sourceAlias = approval.sourceAlias
		val windowStart = approval.sourceWindowStart
		val windowEnd = approval.sourceWindowEnd
		if (!Regex("^source-[a-f0-9]{16,64}$").matches(sourceAlias) || writingBlockIds.size !in 1..20 ||
			writingBlockIds.distinct().size != writingBlockIds.size || !windowStart.isBefore(windowEnd)
		) throw CertificationImportedSourcePreflightException()
		val rows = writingBlockIds.map { blockId ->
			jdbcTemplate.query(
				"""
				select id, title, body, coalesce(nullif(canonical_url, ''), url), platform, source_kind, content_hash,
				       source_created_at, source_updated_at
				from writing_blocks where workspace_id = ? and id = ? and status = 'ACTIVE' and source_origin = 'integration'
				""".trimIndent(),
				{ rs, _ -> ImportedRow(
					rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
					rs.getString(6), rs.getString(7), rs.getTimestamp(8)?.toInstant(), rs.getTimestamp(9)?.toInstant(),
				) },
				devContext.devWorkspaceId, blockId,
			).singleOrNull() ?: throw CertificationImportedSourcePreflightException()
		}
		if (rows.any { row ->
			val sourceTime = row.sourceUpdatedAt ?: row.sourceCreatedAt
			!canonicalGitHubUrl(row.url) || row.url !in approval.approvedOriginalUrls ||
				row.platform.lowercase() != "github" || row.sourceKind.isBlank() || row.contentHash.isBlank() ||
				sourceTime == null || sourceTime.isBefore(windowStart) || sourceTime.isAfter(windowEnd) ||
				sourceTime.isAfter(approval.approvedAt)
		}) throw CertificationImportedSourcePreflightException()
		val codes = rows.flatMap { row ->
			SensitiveSourcePreflight().scan(ApprovedSourceSnapshot(
				sourceAlias, listOf(row.platform, row.sourceKind, row.title, row.body),
			)).codes
		}.toSortedSet().toList()
		return ImportedSourcePreflightResult(
			sourceAlias = sourceAlias,
			writingBlockCount = rows.size,
			sourceSnapshotSetHash = CertificationSourceSnapshotSetHash.compute(rows.map { row ->
				CertificationSourceSnapshotIdentity(
					row.writingBlockId, row.platform.uppercase(), row.sourceKind, row.url, row.contentHash,
					row.sourceCreatedAt, row.sourceUpdatedAt,
				)
			}),
			eligible = codes.isEmpty(),
			codes = codes,
		)
	}

	private data class ImportedRow(
		val writingBlockId: UUID,
		val title: String,
		val body: String,
		val url: String,
		val platform: String,
		val sourceKind: String,
		val contentHash: String,
		val sourceCreatedAt: Instant?,
		val sourceUpdatedAt: Instant?,
	)

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			if (args.isNotEmpty()) throw CertificationImportedSourcePreflightException()
			val env = System.getenv()
			val forbidden = setOf(
				"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
				"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
			)
			if (forbidden.any(env::containsKey)) throw CertificationImportedSourcePreflightException()
			fun required(name: String) = env[name]?.takeIf(String::isNotBlank) ?: throw CertificationImportedSourcePreflightException()
			val jdbc = JdbcTemplate(DriverManagerDataSource(
				required("PLOT_CERTIFICATION_DATABASE_URL"), required("PLOT_CERTIFICATION_DATABASE_USERNAME"),
				required("PLOT_CERTIFICATION_DATABASE_PASSWORD"),
			))
			CertificationDatabaseTargetPolicy.validate(
				required("PLOT_CERTIFICATION_DATABASE_URL"), required("PLOT_CERTIFICATION_DATABASE_NAME"),
				required("PLOT_CERTIFICATION_DATABASE_FINGERPRINT"), required("PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"), jdbc,
			)
			val ids = required("PLOT_CERTIFICATION_WRITING_BLOCK_IDS").split(',').map {
				runCatching { UUID.fromString(it) }.getOrElse { throw CertificationImportedSourcePreflightException() }
			}
			val mapper = jacksonObjectMapper()
			val approvalPath = Path.of(required("PLOT_CERTIFICATION_SOURCE_APPROVAL"))
			if (!approvalPath.isAbsolute || !Files.isRegularFile(approvalPath, LinkOption.NOFOLLOW_LINKS) ||
				Files.isSymbolicLink(approvalPath) || Files.size(approvalPath) !in 1..64L * 1024L
			) throw CertificationImportedSourcePreflightException()
			val approval = Files.newInputStream(approvalPath, LinkOption.NOFOLLOW_LINKS).use(mapper::readTree)
				.let(CertificationSourceApprovalContract::read)
			if (approval.sourceAlias != required("PLOT_CERTIFICATION_SOURCE_ALIAS") ||
				approval.sourceWindowStart != Instant.parse(required("PLOT_CERTIFICATION_SOURCE_WINDOW_START")) ||
				approval.sourceWindowEnd != Instant.parse(required("PLOT_CERTIFICATION_SOURCE_WINDOW_END")) ||
				approval.approvedAt.isAfter(Instant.parse(required("PLOT_CERTIFICATION_STARTED_AT")))
			) throw CertificationImportedSourcePreflightException()
			val result = CertificationImportedSourcePreflight(jdbc).inspect(approval, ids)
			if (!result.eligible) throw CertificationImportedSourcePreflightException()
			val target = Path.of(required("PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT_OUTPUT"))
			if (!target.isAbsolute) throw CertificationImportedSourcePreflightException()
			Files.createDirectories(target.parent)
			Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
				mapper.writeValue(it, result)
			}
		}
	}
}

class CertificationImportedSourcePreflightException : IllegalStateException("CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT_REJECTED")

private fun canonicalGitHubUrl(value: String): Boolean = runCatching { URI(value) }.getOrNull()?.let { uri ->
	uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
		uri.fragment == null && uri.rawQuery == null && !uri.path.isNullOrBlank()
} == true
