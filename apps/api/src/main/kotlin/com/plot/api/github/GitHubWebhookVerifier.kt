package com.plot.api.github

import com.plot.api.common.ApiException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class GitHubWebhookVerifier(private val properties: GitHubProperties) {
	fun verify(rawBody: ByteArray, signatureHeader: String?) {
		val secret = properties.webhookSecret?.takeIf { it.isNotBlank() }
			?: throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_WEBHOOK_NOT_CONFIGURED",
				"GitHub webhook processing is not configured",
			)
		val signature = signatureHeader?.let(signaturePattern::matchEntire)?.groupValues?.get(1)
			?: invalidSignature()
		val actual = HexFormat.of().parseHex(signature)
		val mac = Mac.getInstance(HMAC_SHA256)
		mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_SHA256))
		val expected = mac.doFinal(rawBody)
		if (!MessageDigest.isEqual(expected, actual)) invalidSignature()
	}

	private fun invalidSignature(): Nothing = throw ApiException(
		HttpStatus.UNAUTHORIZED,
		"INVALID_GITHUB_WEBHOOK",
		"GitHub webhook signature is invalid",
	)

	private companion object {
		const val HMAC_SHA256 = "HmacSHA256"
		val signaturePattern = Regex("sha256=([0-9a-fA-F]{64})")
	}
}
