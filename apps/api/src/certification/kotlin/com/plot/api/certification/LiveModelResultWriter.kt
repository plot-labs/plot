package com.plot.api.certification

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class MachineResultValidationException : IllegalArgumentException("Machine result rejected by the allow-list")
class ImmutableEvidenceWriteException : IllegalStateException("Immutable evidence already exists")

/** Rejects model evidence outside the narrow machine-result contract before immutable storage. */
class MachineResultAllowListValidator(
	private val contract: CertificationArtifactContract = CertificationArtifactContract(),
) {
	fun validateEvidence(
		node: JsonNode,
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>? = null,
		prior: EvidenceEnvelope? = null,
	): EvidenceEnvelope {
		try {
			rejectSensitiveText(node)
			return contract.readEvidence(node, campaign, execution, prior)
		} catch (_: CertificationArtifactViolation) {
			throw MachineResultValidationException()
		}
	}

	private fun rejectSensitiveText(node: JsonNode) {
		when {
			node.isString -> if (FORBIDDEN_TEXT.any { it.containsMatchIn(node.stringValue()) }) {
				throw MachineResultValidationException()
			}
			node.isArray -> node.asArray().values().forEach(::rejectSensitiveText)
			node.isObject -> node.properties().forEach { (_, value) -> rejectSensitiveText(value) }
		}
	}

	companion object {
		private val FORBIDDEN_TEXT = listOf(
			Regex("(?i)https?://"),
			Regex("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----", RegexOption.IGNORE_CASE),
			Regex("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16})"),
			Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])"),
		)
	}
}

class LiveModelResultWriter(
	private val certificationRoot: Path,
	private val mapper: ObjectMapper = ObjectMapper(),
	private val validator: MachineResultAllowListValidator =
		MachineResultAllowListValidator(CertificationArtifactContract(mapper)),
) {
	fun write(
		node: JsonNode,
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>? = null,
		prior: EvidenceEnvelope? = null,
	): Path {
		val evidence = validator.validateEvidence(node, campaign, execution, prior)
		val directory = certificationRoot.resolve(campaign.artifact.campaignId).resolve("evidence").normalize()
		val target = directory.resolve("${evidence.artifactId}.json").normalize()
		if (!target.startsWith(directory)) throw MachineResultValidationException()
		Files.createDirectories(directory)
		try {
			Files.newBufferedWriter(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
				mapper.writeValue(output, node)
			}
		} catch (_: FileAlreadyExistsException) {
			throw ImmutableEvidenceWriteException()
		}
		return target
	}
}
