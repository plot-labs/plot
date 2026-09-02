package com.plot.api.auth.oauth

import com.plot.api.auth.PlotAuthProperties
import com.plot.api.common.UuidGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

class OAuthStateCodecTest {
	private val objectMapper: ObjectMapper = jacksonObjectMapper()

	@Test
	fun roundTripsRedirectPathWithSignedState() {
		val codec = OAuthStateCodec(
			objectMapper = objectMapper,
			uuidGenerator = UuidGenerator(),
			authProperties = PlotAuthProperties(githubClientSecret = "test-secret"),
			oauthStateNonceStore = OAuthStateNonceStore(),
		)

		val encoded = codec.encode("/dashboard")
		assertEquals("/dashboard", codec.decode(encoded))
	}

	@Test
	fun rejectsReplayedState() {
		val nonceStore = OAuthStateNonceStore()
		val codec = OAuthStateCodec(
			objectMapper = objectMapper,
			uuidGenerator = UuidGenerator(),
			authProperties = PlotAuthProperties(githubClientSecret = "test-secret"),
			oauthStateNonceStore = nonceStore,
		)

		val encoded = codec.encode("/dashboard")
		codec.decode(encoded)
		assertFailsWith<IllegalArgumentException> { codec.decode(encoded) }
	}

	@Test
	fun rejectsTamperedSignature() {
		val codec = OAuthStateCodec(
			objectMapper = objectMapper,
			uuidGenerator = UuidGenerator(),
			authProperties = PlotAuthProperties(githubClientSecret = "test-secret"),
			oauthStateNonceStore = OAuthStateNonceStore(),
		)

		val encoded = codec.encode("/dashboard")
		val tampered = encoded.substringBeforeLast('.') + ".invalid"
		assertFailsWith<IllegalArgumentException> { codec.decode(tampered) }
	}

	@Test
	fun rejectsMalformedSignatureEncoding() {
		val codec = OAuthStateCodec(
			objectMapper = objectMapper,
			uuidGenerator = UuidGenerator(),
			authProperties = PlotAuthProperties(githubClientSecret = "test-secret"),
			oauthStateNonceStore = OAuthStateNonceStore(),
		)

		val encoded = codec.encode("/dashboard")
		val malformed = encoded.substringBeforeLast('.') + ".%%not-base64%%"
		assertFailsWith<IllegalArgumentException> { codec.decode(malformed) }
	}
}
