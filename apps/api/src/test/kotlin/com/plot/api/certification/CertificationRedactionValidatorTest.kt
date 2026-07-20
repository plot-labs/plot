package com.plot.api.certification

import kotlin.test.Test
import kotlin.test.assertFailsWith
import tools.jackson.module.kotlin.jacksonObjectMapper

class CertificationRedactionValidatorTest {
	private val validator = CertificationRedactionValidator()
	private val mapper = jacksonObjectMapper()

	@Test
	fun `markdown rejects source content credentials private locations raw ids and diagnostics`() {
		listOf(
			"https://github.com/private/repository/pull/7",
			"snapshot excerpt: unreleased customer name",
			"prompt body: do not publish",
			"github_pat_abcdefghijklmnopqrstuvwxyz123456",
			"123e4567-e89b-12d3-a456-426614174000",
			"trace.zip",
			"screenshot",
			"rawRequestId: req_private123",
			"operator notes: arbitrary prose",
		).forEach { unsafe ->
			assertFailsWith<CertificationRedactionException>(unsafe) { validator.validateMarkdown(unsafe) }
		}
	}

	@Test
	fun `only explicitly allow-listed typed hashes can enter markdown`() {
		val allowed = "sha256:${"a".repeat(64)}"
		validator.validateMarkdown("- Corpus hash: `$allowed`", setOf(allowed))
		assertFailsWith<CertificationRedactionException> { validator.validateMarkdown("- Hash: `$allowed`") }
	}

	@Test
	fun `serialized report input rejects unknown free-form fields recursively`() {
		val root = mapper.createObjectNode().apply {
			CertificationRedactionValidatorTestFields.root.forEach { field ->
				when (field) {
					"sourceAliases", "models", "browserReconciliations" -> putArray(field)
					"processRestart", "cleanup", "operator" -> putObject(field)
					else -> put(field, "safe")
				}
			}
			put("operatorNarrative", "private release note")
		}

		assertFailsWith<CertificationRedactionException> { validator.validateSerializedReportInput(root) }
	}
}

private object CertificationRedactionValidatorTestFields {
	val root = setOf(
		"schemaVersion", "phase", "sourceRevision", "campaignId", "campaignManifestHash", "environmentAlias",
		"sourceAliases", "sourceSnapshotSetHash", "corpusHash", "profileHash", "selectedModelExecutionId", "models", "deterministicOutcome",
		"browserReconciliations", "processRestart", "cleanup", "operator",
	)
}
