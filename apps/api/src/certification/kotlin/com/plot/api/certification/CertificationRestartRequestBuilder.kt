package com.plot.api.certification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.module.kotlin.jacksonObjectMapper

data class CertificationRestartRequest(
	val sourceScopeId: UUID,
	val writingBlockIds: List<UUID>,
	val instruction: String = FIXED_RESTART_INSTRUCTION,
)

object CertificationRestartRequestBuilder {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isNotEmpty()) reject()
		val env = System.getenv()
		if (PROVIDER_CREDENTIALS.any(env::containsKey) || required(env, "PLOT_GENERATION_CERTIFICATION_TOOL") != "true") reject()
		val databaseUrl = required(env, "PLOT_CERTIFICATION_DATABASE_URL")
		val dataSource = DriverManagerDataSource(
			databaseUrl,
			required(env, "PLOT_CERTIFICATION_DATABASE_USERNAME"),
			required(env, "PLOT_CERTIFICATION_DATABASE_PASSWORD"),
		)
		val jdbc = JdbcTemplate(dataSource)
		CertificationDatabaseTargetPolicy.validate(
			databaseUrl,
			required(env, "PLOT_CERTIFICATION_DATABASE_NAME"),
			required(env, "PLOT_CERTIFICATION_DATABASE_FINGERPRINT"),
			required(env, "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"),
			jdbc,
		)
		val workspaceId = uuid(required(env, "PLOT_CERTIFICATION_WORKSPACE_ID"))
		val blockIds = required(env, "PLOT_CERTIFICATION_WRITING_BLOCK_IDS").split(',').map(::uuid)
		if (blockIds.isEmpty() || blockIds.size > 20 || blockIds.distinct().size != blockIds.size) reject()
		val placeholders = blockIds.joinToString(",") { "?" }
		val parameters = mutableListOf<Any>(workspaceId).apply { addAll(blockIds); add(blockIds.size) }.toTypedArray()
		val scopes = jdbc.query(
			"""
			select source_scope_id from writing_block_scopes
			where workspace_id = ? and status = 'ACTIVE' and writing_block_id in ($placeholders)
			group by source_scope_id having count(distinct writing_block_id) = ?
			order by source_scope_id
			""".trimIndent(),
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			*parameters,
		)
		if (scopes.size != 1) reject()
		val bytes = jacksonObjectMapper().writeValueAsBytes(CertificationRestartRequest(scopes.single(), blockIds))
		writeNew(Path.of(required(env, "PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST")), bytes)
	}

	private fun writeNew(target: Path, bytes: ByteArray) {
		if (!target.isAbsolute || bytes.isEmpty() || bytes.size > 16_384) reject()
		val normalized = target.normalize()
		val parent = normalized.parent ?: reject()
		Files.createDirectories(parent)
		if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(normalized)) reject()
		try {
			Files.newOutputStream(normalized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
		} catch (_: Exception) {
			reject()
		}
	}

	private fun required(env: Map<String, String>, name: String): String = env[name]?.takeIf(String::isNotBlank) ?: reject()
	private fun uuid(value: String): UUID = runCatching { UUID.fromString(value) }.getOrElse { reject() }
	private fun reject(): Nothing = throw CertificationRestartRequestException()

	private val PROVIDER_CREDENTIALS = setOf(
		"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
		"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
	)
}

class CertificationRestartRequestException : IllegalStateException("CERTIFICATION_RESTART_REQUEST_REJECTED")

private const val FIXED_RESTART_INSTRUCTION = "Generate a concise changelog grounded only in the selected evidence."
