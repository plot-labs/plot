package com.plot.api.github

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

/** Product-owned GitHub access material. It is deliberately unrelated to WorkOS identity. */
data class GitHubProductCredential(
	val id: UUID,
	val userId: UUID,
	val githubAccountId: Long,
	val githubLogin: String?,
	val accessToken: String,
	val refreshToken: String?,
	val scope: String,
	val status: String,
	val createdAt: java.time.Instant,
	val updatedAt: java.time.Instant,
	val revokedAt: java.time.Instant?,
	val encryptionKeyVersion: String,
)

class GitHubCredentialEncryptionException(cause: Throwable? = null) :
	RuntimeException("GitHub product credential could not be encrypted or decrypted", cause)

/** AES-GCM envelope for product credentials; raw tokens never enter logs or JSON responses. */
@Component
class GitHubProductCredentialCipher(
	private val properties: GitHubProperties,
	private val random: SecureRandom = SecureRandom(),
) {
	fun encrypt(value: String, keyVersion: String = properties.productCredentialEncryptionKeyVersion): String {
		if (value.isBlank()) throw GitHubCredentialEncryptionException()
		return try {
			val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.ENCRYPT_MODE, key(keyVersion), GCMParameterSpec(TAG_BITS, nonce))
			cipher.updateAAD(keyVersion.toByteArray(StandardCharsets.UTF_8))
			val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
			Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + encrypted)
		} catch (failure: GitHubCredentialEncryptionException) {
			throw failure
		} catch (failure: Exception) {
			throw GitHubCredentialEncryptionException(failure)
		}
	}

	fun decrypt(value: String, keyVersion: String): String {
		return try {
			val envelope = Base64.getUrlDecoder().decode(value)
			if (envelope.size <= NONCE_BYTES) throw GitHubCredentialEncryptionException()
			val nonce = envelope.copyOfRange(0, NONCE_BYTES)
			val ciphertext = envelope.copyOfRange(NONCE_BYTES, envelope.size)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, key(keyVersion), GCMParameterSpec(TAG_BITS, nonce))
			cipher.updateAAD(keyVersion.toByteArray(StandardCharsets.UTF_8))
			String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
		} catch (failure: GitHubCredentialEncryptionException) {
			throw failure
		} catch (failure: Exception) {
			throw GitHubCredentialEncryptionException(failure)
		}
	}

	private fun key(keyVersion: String): SecretKeySpec {
		if (keyVersion != properties.productCredentialEncryptionKeyVersion) {
			throw GitHubCredentialEncryptionException()
		}
		val configured = properties.productCredentialEncryptionKey?.trim().orEmpty()
		if (configured.isBlank()) throw GitHubCredentialEncryptionException()
		val bytes = decodeKey(configured)
		if (bytes.size != KEY_BYTES) throw GitHubCredentialEncryptionException()
		return SecretKeySpec(bytes, "AES")
	}

	private fun decodeKey(value: String): ByteArray {
		val base64 = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
		if (base64?.size == KEY_BYTES) return base64
		return value.toByteArray(StandardCharsets.UTF_8)
	}

	private companion object {
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val KEY_BYTES = 32
		const val NONCE_BYTES = 12
		const val TAG_BITS = 128
	}
}
