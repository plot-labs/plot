package com.plot.api.certification

import com.plot.api.ApiApplication
import com.plot.api.generation.DurableGenerationCheckpoint
import com.plot.api.generation.GenerationCheckpointObserver
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

data class CertificationRestartMarker(
	val schemaVersion: String = "certification-restart-marker-v1",
	val campaignId: String,
	val campaignManifestHash: String,
	val modelExecutionId: String,
	val modelExecutionManifestHash: String,
	val attemptId: String,
	val checkpointArtifact: String,
	val sourceSnapshotSetHash: String,
	val processId: Long,
)

class CertificationProcessCheckpointObserver(
	private val gate: CertificationCheckpointGate,
	private val marker: CertificationRestartMarker,
	private val markerPath: Path,
	private val mapper: ObjectMapper,
) : GenerationCheckpointObserver {
	override fun afterDurableCheckpoint(checkpoint: DurableGenerationCheckpoint) {
		if (checkpoint.artifactType != marker.checkpointArtifact) return
		val target = markerPath.toAbsolutePath().normalize()
		val parent = target.parent ?: throw CertificationRestartMarkerException()
		Files.createDirectories(parent)
		if (Files.isSymbolicLink(parent) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw CertificationRestartMarkerException()
		}
		try {
			Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
				mapper.writeValue(it, marker)
			}
		} catch (_: Exception) {
			throw CertificationRestartMarkerException()
		}
		gate.afterDurableCheckpoint(checkpoint)
	}
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "plot.certification.restart", name = ["enabled"], havingValue = "true")
class CertificationRestartConfiguration {
	@Bean
	fun certificationCheckpointObserver(mapper: ObjectMapper, jdbcTemplate: JdbcTemplate): GenerationCheckpointObserver {
		val env = System.getenv()
		if (required(env, "PLOT_GENERATION_CERTIFICATION_TOOL") != "true" ||
			required(env, "SERVER_ADDRESS") !in setOf("127.0.0.1", "::1") ||
			required(env, "PLOT_CERTIFICATION_MANAGEMENT_SERVER_ADDRESS") !in setOf("127.0.0.1", "::1") ||
			required(env, "PLOT_DEV_BOOTSTRAP_ENABLED") != "false"
		) throw CertificationRestartMarkerException()
		CertificationDatabaseTargetPolicy.validate(
			required(env, "PLOT_CERTIFICATION_DATABASE_URL"),
			required(env, "PLOT_CERTIFICATION_DATABASE_NAME"),
			required(env, "PLOT_CERTIFICATION_DATABASE_FINGERPRINT"),
			required(env, "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"),
			jdbcTemplate,
		)
		val campaignId = required(env, "PLOT_CERTIFICATION_CAMPAIGN_ID")
		val checkpoint = required(env, "PLOT_CERTIFICATION_RESTART_CHECKPOINT")
		if (checkpoint !in setOf("WRITER_OUTPUT", "REVIEWER_OUTPUT", "REWRITER_OUTPUT")) {
			throw CertificationRestartMarkerException()
		}
		val authorization = AuthorizedCertification(campaignId)
		val outputRoot = Path.of(required(env, "PLOT_CERTIFICATION_OUTPUT_ROOT"))
		if (!outputRoot.isAbsolute || Files.isSymbolicLink(outputRoot) || !Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw CertificationRestartMarkerException()
		}
		val expectedMarker = outputRoot.toAbsolutePath().normalize()
			.resolve(campaignId).resolve("restart").resolve("restart-marker.json").normalize()
		val suppliedMarker = Path.of(required(env, "PLOT_CERTIFICATION_RESTART_MARKER")).toAbsolutePath().normalize()
		if (suppliedMarker != expectedMarker || !suppliedMarker.startsWith(outputRoot.toAbsolutePath().normalize())) {
			throw CertificationRestartMarkerException()
		}
		return CertificationProcessCheckpointObserver(
			CertificationCheckpointGate(authorization, checkpoint, CertificationCheckpointGateMode.PAUSE, Duration.ofMinutes(10)),
			CertificationRestartMarker(
				campaignId = campaignId,
				campaignManifestHash = required(env, "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH"),
				modelExecutionId = required(env, "PLOT_CERTIFICATION_MODEL_EXECUTION_ID"),
				modelExecutionManifestHash = required(env, "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH"),
				attemptId = required(env, "PLOT_CERTIFICATION_ATTEMPT_ID"),
				checkpointArtifact = checkpoint,
				sourceSnapshotSetHash = required(env, "PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH"),
				processId = ProcessHandle.current().pid(),
			),
			suppliedMarker,
			mapper,
		)
	}

	private fun required(env: Map<String, String>, key: String): String = env[key]?.takeIf(String::isNotBlank)
		?: throw CertificationRestartMarkerException()
}

object CertificationRestartApiLauncher {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isNotEmpty()) throw CertificationRestartMarkerException()
		SpringApplicationBuilder(ApiApplication::class.java, CertificationRestartConfiguration::class.java)
			.properties("plot.certification.restart.enabled=true")
			.run()
	}
}

class CertificationRestartMarkerException : IllegalStateException("CERTIFICATION_RESTART_MARKER_REJECTED")
