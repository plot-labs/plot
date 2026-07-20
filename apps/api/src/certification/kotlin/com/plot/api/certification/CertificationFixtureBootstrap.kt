package com.plot.api.certification

import com.plot.api.dev.DevContext
import com.plot.api.common.JdbcTime.timestamp
import com.plot.api.source.ImportedWritingBlock
import com.plot.api.writingblock.WritingBlockImportService
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

data class CertificationWorkflowCase(
	val scenarioId: String,
	val provider: String,
	val sourceKind: String,
	val title: String,
	val body: String,
)

data class CertificationWorkflowCases(val schemaVersion: String, val cases: List<CertificationWorkflowCase>)

data class CertificationFixtureSource(
	val alias: String,
	val scenarioId: String,
	val writingBlockId: UUID,
	val sourceScopeId: UUID,
)

data class CertificationFixtureManifest(
	val campaignId: String,
	val modelExecutionId: String,
	val idempotencyNamespace: String,
	val sourceSnapshotSetHash: String,
	val sources: List<CertificationFixtureSource>,
)

class CertificationSnapshotDriftException : IllegalStateException("CERTIFICATION_SOURCE_SNAPSHOT_DRIFT")
class CertificationFixtureException : IllegalStateException("CERTIFICATION_FIXTURE_REJECTED")

class CertificationDatabaseInspector(private val jdbcTemplate: JdbcTemplate) {
	fun inspect(disposableToken: String): CertificationDatabaseBaseline {
		val databaseName = jdbcTemplate.queryForObject("select current_database()", String::class.java)
			?: throw CertificationActivationException()
		val databaseHost = jdbcTemplate.queryForObject(
			"select coalesce(inet_server_addr()::text, '127.0.0.1')",
			String::class.java,
		) ?: throw CertificationActivationException()
		val count = listOf("users", "workspaces", "writing_blocks", "generation_runs").sumOf { table ->
			jdbcTemplate.queryForObject("select count(*) from $table", Long::class.java) ?: 0L
		}
		return CertificationDatabaseBaseline(
			databaseName,
			databaseHost,
			certificationDatabaseFingerprint(databaseName, disposableToken),
			count,
		)
	}
}

/** Certification-only fixture loader. It reaches Writing Blocks through the production importer. */
class CertificationFixtureBootstrap(
	private val authorization: AuthorizedCertification,
	private val jdbcTemplate: JdbcTemplate,
	private val writingBlockImportService: WritingBlockImportService,
	private val devContext: DevContext,
	private val mapper: ObjectMapper = ObjectMapper(),
	private val clock: Clock = Clock.systemUTC(),
) {
	fun bootstrap(modelExecutionId: String): CertificationFixtureManifest {
		if (!MODEL_EXECUTION_ID.matches(modelExecutionId)) throw CertificationFixtureException()
		val cases = loadCases()
		seedCertificationPrincipal()
		val sources = cases.map { workflowCase -> importCase(modelExecutionId, workflowCase) }
		val hash = snapshotSetHash(sources.map { it.writingBlockId })
		return CertificationFixtureManifest(
			campaignId = authorization.certificationId,
			modelExecutionId = modelExecutionId,
			idempotencyNamespace = opaque("namespace", "${authorization.certificationId}:$modelExecutionId:idempotency"),
			sourceSnapshotSetHash = hash,
			sources = sources,
		)
	}

	fun verifySnapshotSet(manifest: CertificationFixtureManifest) {
		if (manifest.campaignId != authorization.certificationId ||
			manifest.sourceSnapshotSetHash != snapshotSetHash(manifest.sources.map { it.writingBlockId })
		) throw CertificationSnapshotDriftException()
	}

	private fun loadCases(): List<CertificationWorkflowCase> {
		val stream = javaClass.classLoader.getResourceAsStream("generation-workflow-cases.json")
			?: throw CertificationFixtureException()
		val payload = stream.use { mapper.readValue(it, CertificationWorkflowCases::class.java) }
		if (payload.schemaVersion != "generation-workflow-cases-v1" || payload.cases.isEmpty() ||
			payload.cases.map { it.scenarioId }.distinct().size != payload.cases.size ||
			payload.cases.any {
				it.scenarioId.isBlank() || it.provider.uppercase() !in setOf("GITHUB", "SLACK", "LINEAR") ||
				it.title.isBlank() || it.body.isBlank()
			}
		) throw CertificationFixtureException()
		return payload.cases
	}

	private fun seedCertificationPrincipal() {
		val now = clock.instant()
		jdbcTemplate.update(
			"insert into users (id, email, display_name, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?) on conflict (id) do nothing",
			devContext.devUserId, "certification@invalid.example", "Certification", timestamp(now), timestamp(now),
		)
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, ?, ?, ?, 'ACTIVE', ?, ?) on conflict (id) do nothing",
			devContext.devWorkspaceId, "Certification", "cert-${shortHash(authorization.certificationId)}", devContext.devUserId, timestamp(now), timestamp(now),
		)
		jdbcTemplate.update(
			"insert into workspace_members (id, workspace_id, user_id, role, status, joined_at, created_at, updated_at) values (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, ?) on conflict (workspace_id, user_id) do nothing",
			devContext.devWorkspaceMemberId, devContext.devWorkspaceId, devContext.devUserId, timestamp(now), timestamp(now), timestamp(now),
		)
	}

	private fun importCase(modelExecutionId: String, workflowCase: CertificationWorkflowCase): CertificationFixtureSource {
		val identity = "${authorization.certificationId}:$modelExecutionId:${workflowCase.scenarioId}:${workflowCase.provider}"
		val namespaceId = stableUuid("namespace:$identity")
		val scopeId = stableUuid("scope:$identity")
		val observationId = stableUuid("observation:$identity")
		val provider = workflowCase.provider.uppercase()
		val now = clock.instant()
		jdbcTemplate.update(
			"""
			insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'CERTIFICATION', ?, ?, 'ACTIVE', ?, ?) on conflict (workspace_id, id) do nothing
			""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, provider, opaque("namespace", identity), opaque("source", identity), timestamp(now), timestamp(now),
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, ?, 'CORPUS', 'CERTIFICATION', ?, ?, 'ACTIVE', ?, ?) on conflict (workspace_id, id) do nothing
			""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, provider, opaque("scope", identity), opaque("source", "scope:$identity"), timestamp(now), timestamp(now),
		)
		jdbcTemplate.update(
			"""
			insert into source_observations (id, workspace_id, source_scope_id, authority_owner, coverage_key,
			 observation_mode, generation, status, started_at, completed_at, created_at)
			values (?, ?, ?, ?, ?, 'COMPLETE', 0, 'COMPLETED', ?, ?, ?)
			on conflict (workspace_id, authority_owner, coverage_key, generation) do nothing
			""".trimIndent(),
			observationId, devContext.devWorkspaceId, scopeId, opaque("authority", identity), opaque("coverage", identity), timestamp(now), timestamp(now), timestamp(now),
		)
		val alias = opaque("source", identity)
		val sourceInstant = Instant.parse("2026-07-01T00:00:00Z")
		val result = writingBlockImportService.upsert(
			ImportedWritingBlock(
				sourceNamespaceId = namespaceId,
				sourceScopeId = scopeId,
				observationId = observationId,
				externalObjectKey = opaque("object", identity),
				sourceOrigin = "certification",
				sourceKind = workflowCase.sourceKind,
				title = workflowCase.title,
				body = workflowCase.body,
				url = "https://example.invalid/$alias",
				canonicalUrl = "https://example.invalid/$alias",
				author = null,
				platform = provider.lowercase(),
				metadata = mapOf("sourceAlias" to alias),
				sourceCreatedAt = sourceInstant,
				sourceUpdatedAt = sourceInstant,
			),
			now,
		)
		return CertificationFixtureSource(alias, workflowCase.scenarioId, result.blockId, scopeId)
	}

	private fun snapshotSetHash(blockIds: List<UUID>): String {
		if (blockIds.isEmpty() || blockIds.distinct().size != blockIds.size) throw CertificationSnapshotDriftException()
		val rows = blockIds.mapNotNull { blockId ->
			jdbcTemplate.query(
				"""
					select id, platform, source_kind, coalesce(nullif(canonical_url, ''), url), content_hash,
					       source_created_at, source_updated_at
					from writing_blocks where workspace_id = ? and id = ?
				""".trimIndent(),
				{ rs, _ -> CertificationSourceSnapshotIdentity(
					rs.getObject(1, UUID::class.java), rs.getString(2).uppercase(), rs.getString(3), rs.getString(4),
					rs.getString(5), rs.getTimestamp(6)?.toInstant(), rs.getTimestamp(7)?.toInstant(),
				) },
				devContext.devWorkspaceId,
				blockId,
			).singleOrNull()
		}
		if (rows.size != blockIds.size) throw CertificationSnapshotDriftException()
		return CertificationSourceSnapshotSetHash.compute(rows)
	}

	private fun stableUuid(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray())
	private fun opaque(prefix: String, value: String): String = "$prefix-${shortHash(value)}"
	private fun shortHash(value: String): String = sha256Hex(value).take(24)
}

fun sha256(value: String): String = "sha256:${sha256Hex(value)}"

private fun sha256Hex(value: String): String = HexFormat.of().formatHex(
	MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
)

private val MODEL_EXECUTION_ID = Regex("^model-execution-[a-f0-9]{16,64}$")
