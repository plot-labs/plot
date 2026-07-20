package com.plot.api.certification

import java.security.MessageDigest

private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "localhost")
private val CERTIFICATION_ID = Regex("^campaign-[a-f0-9]{16,64}$")
private val DATABASE_NAME = Regex("^plot_cert_[a-f0-9]{8,64}$")
private val FINGERPRINT = Regex("^sha256:[a-f0-9]{64}$")

class CertificationActivationException : IllegalStateException("CERTIFICATION_ACTIVATION_REJECTED")

data class CertificationProperties(
	val enabled: Boolean,
	val activeProfiles: Set<String>,
	val serverAddress: String?,
	val managementServerAddress: String?,
	val externalHost: String? = null,
	val forwardedHost: String? = null,
	val devBootstrapEnabled: Boolean,
	val certificationId: String?,
	val expectedDatabaseFingerprint: String?,
)

data class CertificationDatabaseBaseline(
	val databaseName: String,
	val databaseHost: String,
	val databaseFingerprint: String,
	val applicationRowCount: Long,
)

/** Marker returned only after every fail-closed activation check succeeds. */
class AuthorizedCertification internal constructor(val certificationId: String)

class CertificationActivationGuard {
	fun authorize(
		properties: CertificationProperties,
		baseline: CertificationDatabaseBaseline,
	): AuthorizedCertification {
		val id = properties.certificationId
		val managementAddress = properties.managementServerAddress ?: properties.serverAddress
		val accepted = properties.enabled &&
			"generation-certification" in properties.activeProfiles &&
			properties.serverAddress in LOOPBACK_ADDRESSES &&
			managementAddress in LOOPBACK_ADDRESSES &&
			isLoopbackHost(properties.externalHost) &&
			isLoopbackHost(properties.forwardedHost) &&
			!properties.devBootstrapEnabled &&
			id != null && CERTIFICATION_ID.matches(id) &&
			baseline.databaseHost in LOOPBACK_ADDRESSES &&
			DATABASE_NAME.matches(baseline.databaseName) &&
			properties.expectedDatabaseFingerprint != null &&
			FINGERPRINT.matches(properties.expectedDatabaseFingerprint) &&
			MessageDigest.isEqual(
				properties.expectedDatabaseFingerprint.toByteArray(),
				baseline.databaseFingerprint.toByteArray(),
			) &&
			baseline.applicationRowCount == 0L
		if (!accepted) throw CertificationActivationException()
		return AuthorizedCertification(id)
	}

	private fun isLoopbackHost(value: String?): Boolean {
		if (value == null) return true
		return value.split(',').all { entry ->
			val candidate = entry.trim()
			val host = when {
				candidate.startsWith('[') -> candidate.substringAfter('[').substringBefore(']')
				candidate.count { it == ':' } > 1 -> candidate
				else -> candidate.substringBefore(':')
			}
			host in LOOPBACK_ADDRESSES
		}
	}
}

fun certificationDatabaseFingerprint(databaseName: String, disposableToken: String): String {
	return sha256("$databaseName\n$disposableToken")
}
