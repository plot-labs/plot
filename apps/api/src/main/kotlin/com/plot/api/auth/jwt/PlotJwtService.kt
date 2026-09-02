package com.plot.api.auth.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.plot.api.auth.PlotAuthProperties
import com.plot.api.auth.persistence.AuthUserRecord
import com.plot.api.auth.persistence.JwksRecord
import com.plot.api.auth.persistence.JwksRepository
import com.plot.api.common.UuidGenerator
import java.time.Instant
import java.util.Date
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.stereotype.Service

@Service
class PlotJwtService(
	private val jwksRepository: JwksRepository,
	private val authProperties: PlotAuthProperties,
	private val uuidGenerator: UuidGenerator,
) {
	private val databaseDecoder = DatabaseJwksJwtDecoder(jwksRepository, authProperties)

	fun decoder(): JwtDecoder = databaseDecoder

	fun mintToken(user: AuthUserRecord): String {
		val signingKey = ensureSigningKey()
		val ecKey = ECKey.parse(signingKey.privateKey)
		val now = Instant.now()
		val claims = JWTClaimsSet.Builder()
			.subject(user.id)
			.issuer(authProperties.issuer)
			.audience(authProperties.audience)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(authProperties.jwtTtl)))
			.claim("email", user.email)
			.claim("name", user.name)
			.build()
		val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.id).build(), claims)
		signed.sign(ECDSASigner(ecKey.toECPrivateKey()))
		return signed.serialize()
	}

	fun publicJwks(): Map<String, Any> {
		val keys = jwksRepository.findAllActive().map { record ->
			val publicJwk = ECKey.parse(record.publicKey).toPublicJWK()
			publicJwk.toJSONObject()
		}
		return mapOf("keys" to keys)
	}

	private fun ensureSigningKey(): JwksRecord {
		jwksRepository.findSigningKey()?.let { return it }
		val generated = ECKeyGenerator(Curve.P_256)
			.keyID(uuidGenerator.next().toString())
			.generate()
		return jwksRepository.save(JwksRecord(
			id = generated.keyID,
			publicKey = generated.toPublicJWK().toJSONString(),
			privateKey = generated.toJSONString(),
			alg = "ES256",
			createdAt = Instant.now(),
			expiresAt = null,
		))
	}
}

private class DatabaseJwksJwtDecoder(
	private val jwksRepository: JwksRepository,
	private val authProperties: PlotAuthProperties,
) : JwtDecoder {
	@Volatile
	private var cachedDecoder: JwtDecoder? = null

	@Volatile
	private var cachedKeyCount: Int = -1

	override fun decode(token: String): Jwt {
		return try {
			currentDecoder().decode(token)
		} catch (exception: Exception) {
			invalidate()
			currentDecoder().decode(token)
		}
	}

	private fun currentDecoder(): JwtDecoder {
		val keys = jwksRepository.findAllActive()
		if (cachedDecoder != null && cachedKeyCount == keys.size) {
			return cachedDecoder!!
		}
		synchronized(this) {
			val activeKeys = jwksRepository.findAllActive()
			if (cachedDecoder != null && cachedKeyCount == activeKeys.size) {
				return cachedDecoder!!
			}
			if (activeKeys.isEmpty()) {
				throw org.springframework.security.authentication.BadCredentialsException("JWT signing keys are not configured")
			}
			val decoder = NimbusBackedJwtDecoder(activeKeys, authProperties)
			cachedDecoder = decoder
			cachedKeyCount = activeKeys.size
			return decoder
		}
	}

	private fun invalidate() {
		synchronized(this) {
			cachedDecoder = null
			cachedKeyCount = -1
		}
	}
}

private class NimbusBackedJwtDecoder(
	activeKeys: List<JwksRecord>,
	private val authProperties: PlotAuthProperties,
) : JwtDecoder {
	private val processor = DefaultJWTProcessor<SecurityContext>().apply {
		val jwkSet = JWKSet(activeKeys.map { ECKey.parse(it.publicKey).toPublicJWK() })
		jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.ES256, ImmutableJWKSet(jwkSet))
	}
	private val validator = DelegatingOAuth2TokenValidator(
		JwtValidators.createDefaultWithIssuer(authProperties.issuer),
		AudienceValidator(authProperties.audience),
	)

	override fun decode(token: String): Jwt {
		val signedJwt = SignedJWT.parse(token)
		val claimsSet = processor.process(signedJwt, null)
		val jwt = Jwt.withTokenValue(token)
			.headers { headers ->
				signedJwt.header.toJSONObject().forEach { (key, value) ->
					headers[key] = value
				}
			}
			.claims { claims ->
				claimsSet.claims.filterValues { it != null }.forEach { (key, value) ->
					claims[key] = value!!
				}
			}
			.build()
		val result = validator.validate(jwt)
		if (result.hasErrors()) {
			throw org.springframework.security.oauth2.jwt.JwtValidationException(
				token,
				result.errors,
			)
		}
		return jwt
	}
}

private class AudienceValidator(private val audience: String) : OAuth2TokenValidator<Jwt> {
	private val error = OAuth2Error("invalid_token", "The required audience is missing", null)

	override fun validate(token: Jwt): OAuth2TokenValidatorResult = if (token.audience.contains(audience)) {
		OAuth2TokenValidatorResult.success()
	} else {
		OAuth2TokenValidatorResult.failure(error)
	}
}
