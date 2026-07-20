package com.plot.api.certification

import java.util.UUID
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CertificationExportSequenceValidatorTest {
	private val variant = UUID.randomUUID()
	private val user = UUID.randomUUID()

	@Test
	fun `zero and each valid single-event form are allowed`() {
		CertificationExportSequenceValidator.validate(emptyList())
		CertificationExportSequenceValidator.validate(listOf(rejected()))
		CertificationExportSequenceValidator.validate(listOf(succeeded(0, false)))
		CertificationExportSequenceValidator.validate(listOf(succeeded(1, true)))
	}

	@Test
	fun `warning sequence requires attributable rejected then confirmed success`() {
		CertificationExportSequenceValidator.validate(listOf(rejected(), succeeded(1, true)))

		listOf(
			listOf(succeeded(1, true), rejected()),
			listOf(rejected(), succeeded(1, true).copy(createdByUserId = UUID.randomUUID())),
			listOf(rejected(), succeeded(1, true).copy(variantId = UUID.randomUUID())),
			listOf(rejected(), succeeded(1, true).copy(disposition = "DOWNLOAD")),
			listOf(rejected(), succeeded(2, true)),
			listOf(succeeded(0, false), succeeded(0, false)),
			listOf(rejected(), succeeded(1, true).copy(createdAt = Instant.parse("2026-07-16T00:00:00Z"))),
		).forEach { invalid ->
			assertFailsWith<CertificationAuditReconciliationException> { CertificationExportSequenceValidator.validate(invalid) }
		}
	}

	@Test
	fun `failure code acknowledgement and output hash semantics are exact`() {
		listOf(
			rejected().copy(failureCode = null),
			rejected().copy(outputContentHash = hash()),
			rejected().copy(warningAcknowledged = true),
			succeeded(1, false),
			succeeded(1, true).copy(failureCode = "EXPORT_CONFIRMATION_REQUIRED"),
			succeeded(0, false).copy(outputContentHash = null),
		).forEach { invalid ->
			assertFailsWith<CertificationAuditReconciliationException> {
				CertificationExportSequenceValidator.validate(listOf(invalid))
			}
		}
	}

	private fun rejected() = row("REJECTED", 1, false, null, "EXPORT_CONFIRMATION_REQUIRED")
	private fun succeeded(unresolved: Int, acknowledged: Boolean) = row("SUCCEEDED", unresolved, acknowledged, hash(), null)

	private fun row(
		status: String,
		unresolved: Int,
		acknowledged: Boolean,
		outputHash: String?,
		failureCode: String?,
	) = CertificationExportRow(
		UUID.randomUUID(), variant, "COPY", status, unresolved, acknowledged, outputHash, failureCode, user,
		if (status == "REJECTED") Instant.parse("2026-07-16T00:00:00Z") else Instant.parse("2026-07-16T00:00:01Z"),
	)

	private fun hash() = "sha256:${"a".repeat(64)}"
}
