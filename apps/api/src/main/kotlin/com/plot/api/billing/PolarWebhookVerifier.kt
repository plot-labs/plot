package com.plot.api.billing

import com.plot.api.common.ApiException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class PolarWebhookVerifier(
	private val properties: PolarProperties,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun verify(
		webhookId: String,
		webhookTimestamp: String,
		webhookSignature: String,
		rawBody: String,
	) {
		val secret = properties.webhookSecret?.takeIf { it.isNotBlank() }
		if (!properties.enabled || secret == null) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"POLAR_NOT_CONFIGURED",
				"Polar webhook processing is not configured",
			)
		}
		if (webhookId.isBlank() || webhookTimestamp.isBlank() || webhookSignature.isBlank()) invalid()
		val signedAt = webhookTimestamp.toLongOrNull()
			?.let { epochSecond -> runCatching { Instant.ofEpochSecond(epochSecond) }.getOrNull() }
			?: invalid()
		val age = Duration.between(signedAt, clock.instant()).abs()
		if (age > Duration.ofSeconds(properties.timestampToleranceSeconds)) invalid()

		val signedPayload = "$webhookId.$webhookTimestamp.$rawBody"
		val mac = Mac.getInstance(HMAC_ALGORITHM)
		mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
		val expected = mac.doFinal(signedPayload.toByteArray(StandardCharsets.UTF_8))
		val verified = webhookSignature
			.trim()
			.split(SIGNATURE_SEPARATOR)
			.asSequence()
			.mapNotNull(::decodeV1Signature)
			.any { candidate -> MessageDigest.isEqual(expected, candidate) }
		if (!verified) invalid()
	}

	private fun decodeV1Signature(value: String): ByteArray? {
		val parts = value.split(',', limit = 2)
		if (parts.size != 2 || parts[0] != "v1") return null
		return runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull()
	}

	private fun invalid(): Nothing = throw ApiException(
		HttpStatus.UNAUTHORIZED,
		"INVALID_POLAR_WEBHOOK",
		"Polar webhook signature is invalid",
	)

	private companion object {
		const val HMAC_ALGORITHM = "HmacSHA256"
		val SIGNATURE_SEPARATOR = Regex("\\s+")
	}
}
