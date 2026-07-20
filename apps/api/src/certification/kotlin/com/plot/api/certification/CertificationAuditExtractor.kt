package com.plot.api.certification

import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

class CertificationAuditExtractor(
	private val authorization: AuthorizedCertification,
	private val jdbcTemplate: JdbcTemplate,
	private val outputRoot: Path,
	private val mapper: ObjectMapper,
	private val attributor: CertificationInvocationAttributor? = null,
	private val execution: ModelExecutionManifest? = null,
) {
	fun extract(identity: CertificationAttemptIdentity): Path {
		val projected = CertificationAuditProjection(authorization, jdbcTemplate).project(identity)
		val attributed = if (attributor != null && execution != null) {
			projected.copy(invocationAttributions = attributor.attribute(identity, execution))
		} else projected
		return CertificationAuditEnvelopeWriter(outputRoot, mapper).write(attributed)
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			if (args.isNotEmpty()) throw CertificationAuditExtractionException("CERTIFICATION_CLI_ARGUMENT_REJECTED")
			val env = System.getenv()
			if (FORBIDDEN_CREDENTIALS.any(env::containsKey)) reject("CERTIFICATION_CREDENTIAL_SCOPE_REJECTED")
			val database = parseDatabase(required(env, "PLOT_CERTIFICATION_DATABASE_URL"))
			val databaseName = required(env, "PLOT_CERTIFICATION_DATABASE_NAME")
			if (database.name != databaseName || !DATABASE_NAME.matches(databaseName)) reject("CERTIFICATION_DATABASE_REJECTED")
			val expectedFingerprint = required(env, "PLOT_CERTIFICATION_DATABASE_FINGERPRINT")
			val disposableToken = required(env, "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN")
			if (certificationDatabaseFingerprint(databaseName, disposableToken) != expectedFingerprint) {
				reject("CERTIFICATION_DATABASE_FINGERPRINT_REJECTED")
			}
			val dataSource = DriverManagerDataSource(
				required(env, "PLOT_CERTIFICATION_DATABASE_URL"),
				required(env, "PLOT_CERTIFICATION_DATABASE_USERNAME"),
				required(env, "PLOT_CERTIFICATION_DATABASE_PASSWORD"),
			)
			val jdbc = JdbcTemplate(dataSource)
			CertificationDatabaseTargetPolicy.validate(
				required(env, "PLOT_CERTIFICATION_DATABASE_URL"), databaseName, expectedFingerprint, disposableToken, jdbc,
			)
			val campaignId = required(env, "PLOT_CERTIFICATION_CAMPAIGN_ID")
			val workspaceId = uuid(env, "PLOT_CERTIFICATION_WORKSPACE_ID")
			val idempotencyKey = required(env, "PLOT_CERTIFICATION_IDEMPOTENCY_KEY")
			val resolvedRuns = jdbc.query(
				"select id from generation_runs where workspace_id = ? and idempotency_key = ?",
				{ rs, _ -> rs.getObject(1, UUID::class.java) },
				workspaceId, idempotencyKey,
			)
			if (resolvedRuns.size != 1) reject("CERTIFICATION_RUN_RESOLUTION_REJECTED")
			val suppliedRunId = env["PLOT_CERTIFICATION_RUN_ID"]?.takeIf(String::isNotBlank)?.let {
				runCatching { UUID.fromString(it) }.getOrElse { reject("CERTIFICATION_IDENTITY_REJECTED") }
			}
			if (suppliedRunId != null && suppliedRunId != resolvedRuns.single()) reject("CERTIFICATION_RUN_RESOLUTION_REJECTED")
			val identity = CertificationAttemptIdentity(
				campaignId = campaignId,
				campaignManifestHash = required(env, "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH"),
				modelExecutionId = required(env, "PLOT_CERTIFICATION_MODEL_EXECUTION_ID"),
				modelExecutionManifestHash = required(env, "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH"),
				attemptId = required(env, "PLOT_CERTIFICATION_ATTEMPT_ID"),
				scenarioId = required(env, "PLOT_CERTIFICATION_SCENARIO_ID"),
				ordinal = required(env, "PLOT_CERTIFICATION_ATTEMPT_ORDINAL").toIntOrNull()
					?: reject("CERTIFICATION_ORDINAL_REJECTED"),
				workspaceId = workspaceId,
				runId = resolvedRuns.single(),
				idempotencyKey = idempotencyKey,
				sourceSnapshotSetHash = required(env, "PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH"),
			)
			val mapper = jacksonObjectMapper()
			val campaign = CertificationArtifactContract(mapper).sealCampaign(readJson(mapper, required(env, "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST")))
			val execution = CertificationArtifactContract(mapper).sealModelExecution(
				readJson(mapper, required(env, "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST")), campaign,
			)
			if (campaign.artifact.campaignId != campaignId || campaign.hash != identity.campaignManifestHash ||
				execution.artifact.modelExecutionId != identity.modelExecutionId || execution.hash != identity.modelExecutionManifestHash ||
				campaign.artifact.sourceSnapshotSetHash != identity.sourceSnapshotSetHash
			) reject("CERTIFICATION_MANIFEST_IDENTITY_REJECTED")
			val metadataClient = OpenRouterGenerationMetadataClient(
				JdkOpenRouterCertificationTransport.live(required(env, "OPENROUTER_API_KEY")),
			)
			CertificationAuditExtractor(
				AuthorizedCertification(campaignId),
				jdbc,
				Path.of(required(env, "PLOT_CERTIFICATION_OUTPUT_ROOT")),
				mapper,
				CertificationInvocationAttributor(jdbc, mapper) { responseId -> metadataClient.fetch(GenerationMetadataRequest(responseId)) },
				execution.artifact,
			).extract(identity)
		}

		private fun parseDatabase(value: String): DatabaseTarget {
			if (!value.startsWith("jdbc:postgresql://")) reject("CERTIFICATION_DATABASE_REJECTED")
			val uri = runCatching { URI(value.removePrefix("jdbc:")) }.getOrNull()
				?: reject("CERTIFICATION_DATABASE_REJECTED")
			if (uri.scheme != "postgresql" || uri.host !in setOf("127.0.0.1", "::1", "localhost") ||
				uri.port !in 1..65535 || uri.userInfo != null || uri.query != null || uri.fragment != null
			) reject("CERTIFICATION_DATABASE_REJECTED")
			return DatabaseTarget(uri.path.removePrefix("/"))
		}

		private fun required(env: Map<String, String>, name: String): String = env[name]?.takeIf(String::isNotBlank)
			?: reject("CERTIFICATION_ENV_REJECTED")

		private fun uuid(env: Map<String, String>, name: String): UUID = runCatching { UUID.fromString(required(env, name)) }
			.getOrElse { reject("CERTIFICATION_IDENTITY_REJECTED") }

		private fun readJson(mapper: ObjectMapper, value: String) = try {
			val path = Path.of(value)
			if (!path.isAbsolute || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..1_048_576) {
				reject("CERTIFICATION_MANIFEST_INPUT_REJECTED")
			}
			Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(mapper::readTree)
		} catch (failure: CertificationAuditExtractionException) {
			throw failure
		} catch (_: Exception) {
			reject("CERTIFICATION_MANIFEST_INPUT_REJECTED")
		}

		private fun reject(code: String): Nothing = throw CertificationAuditExtractionException(code)

		private data class DatabaseTarget(val name: String)

		private val DATABASE_NAME = Regex("^plot_cert_[a-f0-9]{8,64}$")
		private val FORBIDDEN_CREDENTIALS = setOf(
			"OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_APP_PRIVATE_KEY",
			"GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
			"SPRING_AI_OPENAI_API_KEY", "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL",
		)
	}
}

class CertificationAuditExtractionException(code: String) : IllegalStateException(code)
