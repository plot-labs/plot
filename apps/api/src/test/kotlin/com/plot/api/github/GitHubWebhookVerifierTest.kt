package com.plot.api.github

import com.plot.api.common.ApiException
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GitHubWebhookVerifierTest {
	private val secret = "It's a Secret to Everybody"
	private val payload = "Hello, World!".toByteArray(StandardCharsets.UTF_8)
	private val signature =
		"sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"

	@Test
	fun verifiesGitHubsPublishedHmacSha256Vector() {
		verifier().verify(payload, signature)
	}

	@Test
	fun rejectsAMissingSignature() {
		assertInvalid { verifier().verify(payload, null) }
	}

	@Test
	fun failsClosedWhenTheWebhookSecretIsMissing() {
		val exception = assertFailsWith<ApiException> {
			GitHubWebhookVerifier(GitHubProperties()).verify(payload, signature)
		}
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status)
		assertEquals("GITHUB_WEBHOOK_NOT_CONFIGURED", exception.error)
	}

	@Test
	fun rejectsAMalformedSignaturePrefix() {
		assertInvalid { verifier().verify(payload, signature.replace("sha256=", "sha1=")) }
	}

	@Test
	fun rejectsAWrongDigest() {
		assertInvalid { verifier().verify(payload, "sha256=${"0".repeat(64)}") }
	}

	@Test
	fun verifiesTheOriginalUnicodeRawBytes() {
		val unicodePayload = "{\"release\":{\"name\":\"한글 🚀\"}}".toByteArray(StandardCharsets.UTF_8)
		verifier().verify(unicodePayload, signatureFor(unicodePayload))
	}

	private fun verifier() = GitHubWebhookVerifier(GitHubProperties(webhookSecret = secret))

	private fun signatureFor(rawBody: ByteArray): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody))
	}

	private fun assertInvalid(action: () -> Unit) {
		val exception = assertFailsWith<ApiException> { action() }
		assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
		assertEquals("INVALID_GITHUB_WEBHOOK", exception.error)
	}
}
