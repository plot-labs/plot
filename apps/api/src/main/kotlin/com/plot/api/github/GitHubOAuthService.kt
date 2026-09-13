package com.plot.api.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.plot.api.auth.RequestActorResolver
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

data class GitHubProductOAuthStartResponse(
	val authorizationUrl: String,
	val expiresAt: Instant,
)

data class GitHubProductOAuthCallbackResponse(
	val connectionId: UUID?,
	val returnPath: String,
	val githubAccountLogin: String?,
	val errorCode: String?,
)

data class GitHubProductOAuthToken(
	val accessToken: String,
	val refreshToken: String?,
	val scope: String,
)

data class GitHubProductProfile(
	val id: Long,
	val login: String,
)

interface GitHubProductOAuthClient {
	fun authorizationUrl(state: String): String
	fun exchangeCode(code: String): GitHubProductOAuthToken
	fun fetchProfile(accessToken: String): GitHubProductProfile
}

@Service
class GitHubProductOAuthClientImpl(
	private val properties: GitHubProperties,
) : GitHubProductOAuthClient {
	private val restClient = RestClient.create()

	override fun authorizationUrl(state: String): String {
		val clientId = properties.productOAuthClientId?.trim().takeIf { !it.isNullOrBlank() }
			?: throw notConfigured()
		val redirectUri = properties.productOAuthRedirectUri?.trim().takeIf { !it.isNullOrBlank() }
			?: throw notConfigured()
		return UriComponentsBuilder
			.fromUriString("${properties.webBaseUrl.trimEnd('/')}/login/oauth/authorize")
			.queryParam("client_id", clientId)
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", properties.productOAuthScopes)
			.queryParam("state", state)
			.build()
			.toUriString()
	}

	override fun exchangeCode(code: String): GitHubProductOAuthToken {
		if (code.isBlank()) throw ApiException(HttpStatus.BAD_REQUEST, "GITHUB_CALLBACK_INVALID", "GitHub callback is invalid")
		val clientId = properties.productOAuthClientId?.trim().takeIf { !it.isNullOrBlank() }
			?: throw notConfigured()
		val clientSecret = properties.productOAuthClientSecret?.trim().takeIf { !it.isNullOrBlank() }
			?: throw notConfigured()
		val redirectUri = properties.productOAuthRedirectUri?.trim().takeIf { !it.isNullOrBlank() }
			?: throw notConfigured()
		return try {
			val response = restClient.post()
				.uri("${properties.webBaseUrl.trimEnd('/')}/login/oauth/access_token")
				.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(LinkedMultiValueMap<String, String>().apply {
					add("client_id", clientId)
					add("client_secret", clientSecret)
					add("code", code)
					add("redirect_uri", redirectUri)
				})
				.retrieve()
				.body(GitHubTokenResponse::class.java)
				?: throw providerUnavailable()
			GitHubProductOAuthToken(
				accessToken = response.accessToken?.takeIf { it.isNotBlank() } ?: throw providerUnavailable(),
				refreshToken = response.refreshToken?.takeIf { it.isNotBlank() },
				scope = response.scope.orEmpty(),
			)
		} catch (failure: ApiException) {
			throw failure
		} catch (_: Exception) {
			throw providerUnavailable()
		}
	}

	override fun fetchProfile(accessToken: String): GitHubProductProfile {
		if (accessToken.isBlank()) throw providerUnavailable()
		return try {
			val user = restClient.get()
				.uri("${properties.apiBaseUrl.trimEnd('/')}/user")
				.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
				.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
				.retrieve()
				.body(GitHubUserResponse::class.java)
				?: throw providerUnavailable()
			GitHubProductProfile(user.id, user.login)
		} catch (failure: ApiException) {
			throw failure
		} catch (_: Exception) {
			throw providerUnavailable()
		}
	}

	private fun notConfigured() = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE,
		"GITHUB_PRODUCT_OAUTH_NOT_CONFIGURED",
		"GitHub product authorization is not configured",
	)

	private fun providerUnavailable() = ApiException(
		HttpStatus.BAD_GATEWAY,
		"GITHUB_PROVIDER_UNAVAILABLE",
		"GitHub is temporarily unavailable",
	)

	@JsonIgnoreProperties(ignoreUnknown = true)
	private data class GitHubTokenResponse(
		@JsonProperty("access_token") val accessToken: String? = null,
		@JsonProperty("refresh_token") val refreshToken: String? = null,
		val scope: String? = null,
		val error: String? = null,
	)

	@JsonIgnoreProperties(ignoreUnknown = true)
	private data class GitHubUserResponse(
		val id: Long,
		val login: String,
	)
}

@Service("gitHubProductOAuthService")
class GitHubOAuthService(
	private val guard: GitHubGuard,
	private val properties: GitHubProperties,
	private val actorResolver: RequestActorResolver,
	private val devContext: DevContext,
	private val stateService: GitHubProductOAuthStateService,
	private val oauthClient: GitHubProductOAuthClient,
	private val credentialRepository: GitHubProductCredentialRepository,
	private val transactionExecutor: JooqTransactionExecutor,
	private val connectionService: GitHubConnectionService,
	private val uuidGenerator: UuidGenerator,
) {
	fun start(returnPath: String?): GitHubProductOAuthStartResponse {
		guard.requireEnabled()
		requireProductOAuthConfigured()
		val context = currentOwnerContext()
		val state = stateService.create(context.userId, context.workspaceId, returnPath ?: "/settings/integrations")
		return GitHubProductOAuthStartResponse(oauthClient.authorizationUrl(state.value), state.expiresAt)
	}

	fun complete(code: String, state: String): GitHubProductOAuthCallbackResponse {
		guard.requireEnabled()
		requireProductOAuthConfigured()
		if (code.isBlank() || state.isBlank()) {
			throw ApiException(HttpStatus.BAD_REQUEST, "GITHUB_CALLBACK_INVALID", "GitHub callback is invalid")
		}
		val binding = stateService.consume(state)
		val actor = actorResolver.current()
		if (actor != null && actor.userId != binding.userId) {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "GitHub authorization state does not belong to the authenticated user")
		}
		val token = oauthClient.exchangeCode(code)
		val missingScopes = requiredScopes().filterNot { token.scopeTokens().contains(it) }
		if (missingScopes.isNotEmpty()) {
			throw ApiException(
				HttpStatus.UNAUTHORIZED,
				"GITHUB_SCOPE_REQUIRED",
				"GitHub authorization must include the required product permissions",
			)
		}
		val profile = oauthClient.fetchProfile(token.accessToken)
		val now = Instant.now()
		transactionExecutor.execute {
			credentialRepository.saveActive(GitHubProductCredential(
				id = uuidGenerator.next(),
				userId = binding.userId,
				githubAccountId = profile.id,
				githubLogin = profile.login.takeIf { it.isNotBlank() },
				accessToken = token.accessToken,
				refreshToken = token.refreshToken,
				scope = token.scope.trim(),
				status = "ACTIVE",
				createdAt = now,
				updatedAt = now,
				revokedAt = null,
				encryptionKeyVersion = properties.productCredentialEncryptionKeyVersion,
			))
		}

		return try {
			val connection = connectionService.syncExistingInstallationForProductCredential(binding.userId, binding.workspaceId)
			GitHubProductOAuthCallbackResponse(connection.connectionId, binding.returnPath, profile.login, null)
		} catch (failure: ApiException) {
			GitHubProductOAuthCallbackResponse(null, binding.returnPath, profile.login, failure.error)
		} catch (_: RuntimeException) {
			GitHubProductOAuthCallbackResponse(null, binding.returnPath, profile.login, "GITHUB_PROVIDER_UNAVAILABLE")
		}
	}

	private fun currentOwnerContext(): ProductOAuthOwnerContext {
		val actor = actorResolver.current() ?: return ProductOAuthOwnerContext(devContext.devUserId, devContext.devWorkspaceId)
		val workspace = actorResolver.requireWorkspace()
		if (workspace.role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
		return ProductOAuthOwnerContext(actor.userId, workspace.workspaceId)
	}

	private fun requiredScopes(): Set<String> = properties.productOAuthScopes
		.split(',', ' ', '\t', '\n')
		.map(String::trim)
		.filter(String::isNotBlank)
		.toSet()

	private fun requireProductOAuthConfigured() {
		if (properties.productOAuthClientId.isNullOrBlank() ||
			properties.productOAuthClientSecret.isNullOrBlank() ||
			properties.productOAuthRedirectUri.isNullOrBlank() ||
			properties.productCredentialEncryptionKey.isNullOrBlank()
		) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_PRODUCT_OAUTH_NOT_CONFIGURED",
				"GitHub product authorization is not configured",
			)
		}
	}

	private fun GitHubProductOAuthToken.scopeTokens(): Set<String> = scope
		.split(',', ' ', '\t', '\n')
		.map(String::trim)
		.filter(String::isNotBlank)
		.toSet()

	private data class ProductOAuthOwnerContext(val userId: UUID, val workspaceId: UUID)
}
