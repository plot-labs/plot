package com.plot.api.billing

import com.plot.api.common.ApiException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class PolarWebhookVerifierTest {
	private val clock = Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneOffset.UTC)
	private val body = """{"type":"subscription.active","data":{"id":"sub_test"}}"""

	@Test
	fun rawUtf8SecretMatchesGoldenVector() {
		val verifier = verifier(secret = "c2VjcmV0")

		verifier.verify(
			"msg_test",
			"1784973600",
			"v1,jjVQvx9Ub8gJQ197MNJWaMPA8+bvXJMSBBCw5wRMQGU=",
			body,
		)

		val decodedSecretSignature = assertFailsWith<ApiException> {
			verifier.verify(
				"msg_test",
				"1784973600",
				"v1,OMEAcMAB2Qy96bqsMrUfGtV7859BGAG3Jddsuk/HCOg=",
				body,
			)
		}
		assertEquals("INVALID_POLAR_WEBHOOK", decodedSecretSignature.error)
	}

	@Test
	fun tamperedPayloadAndSignatureAreRejected() {
		val secret = "polar_whs_test_secret"
		val verifier = verifier(secret)
		val signature = sign(secret, "msg_test", "1784973600", body)

		assertInvalid {
			verifier.verify("msg_test", "1784973600", "v1,${signature.dropLast(2)}AA", body)
		}
		assertInvalid {
			verifier.verify("msg_test", "1784973600", "v1,$signature", "$body ")
		}
	}

	@Test
	fun timestampsOutsideToleranceAreRejectedInBothDirections() {
		val secret = "polar_whs_test_secret"
		val verifier = verifier(secret)

		listOf("1784973240", "1784973960").forEach { timestamp ->
			assertInvalid {
				verifier.verify(
					"msg_test",
					timestamp,
					"v1,${sign(secret, "msg_test", timestamp, body)}",
					body,
				)
			}
		}
	}

	@Test
	fun anyValidV1SignaturePassesDuringRotation() {
		val secret = "polar_whs_test_secret"
		val verifier = verifier(secret)
		val valid = sign(secret, "msg_test", "1784973600", body)

		verifier.verify(
			"msg_test",
			"1784973600",
			"v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= v1,$valid",
			body,
		)
	}

	@Test
	fun disabledOrMissingSecretReturnsServiceUnavailable() {
		listOf(
			PolarProperties(enabled = false, webhookSecret = "configured"),
			PolarProperties(enabled = true, webhookSecret = null),
		).forEach { properties ->
			val exception = assertFailsWith<ApiException> {
				PolarWebhookVerifier(properties, clock).verify(
					"msg_test",
					"1784973600",
					"v1,signature",
					body,
				)
			}
			assertEquals(503, exception.status.value())
			assertEquals("POLAR_NOT_CONFIGURED", exception.error)
		}
	}

	private fun verifier(secret: String): PolarWebhookVerifier =
		PolarWebhookVerifier(PolarProperties(enabled = true, webhookSecret = secret), clock)

	private fun assertInvalid(block: () -> Unit) {
		val exception = assertFailsWith<ApiException>(block = block)
		assertEquals(401, exception.status.value())
		assertEquals("INVALID_POLAR_WEBHOOK", exception.error)
	}

	private fun sign(secret: String, webhookId: String, timestamp: String, payload: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
		val signature = mac.doFinal("$webhookId.$timestamp.$payload".toByteArray(StandardCharsets.UTF_8))
		return Base64.getEncoder().encodeToString(signature)
	}
}
