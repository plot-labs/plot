package com.plot.api.auth.oauth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.plot.api.auth.PlotAuthProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

data class GitHubProfile(
	val id: String,
	val login: String,
	val name: String?,
	val email: String?,
	val avatarUrl: String?,
)

@Service
class GitHubOAuthService(
	private val authProperties: PlotAuthProperties,
) {
	private val restClient = RestClient.create()

	fun authorizationUrl(state: String): String = UriComponentsBuilder
		.fromUriString("https://github.com/login/oauth/authorize")
		.queryParam("client_id", authProperties.githubClientId)
		.queryParam("redirect_uri", callbackUrl())
		.queryParam("scope", "read:user user:email")
		.queryParam("state", state)
		.build()
		.toUriString()

	fun exchangeCode(code: String): String {
		val response = restClient.post()
			.uri("https://github.com/login/oauth/access_token")
			.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(LinkedMultiValueMap<String, String>().apply {
				add("client_id", authProperties.githubClientId)
				add("client_secret", authProperties.githubClientSecret)
				add("code", code)
				add("redirect_uri", callbackUrl())
			})
			.retrieve()
			.body(GitHubTokenResponse::class.java)
			?: throw IllegalStateException("GitHub token exchange failed")
		return response.accessToken?.takeIf { it.isNotBlank() }
			?: throw IllegalStateException(response.error ?: "GitHub token exchange failed")
	}

	fun fetchProfile(accessToken: String): GitHubProfile {
		val user = restClient.get()
			.uri("https://api.github.com/user")
			.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
			.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
			.retrieve()
			.body(GitHubUserResponse::class.java)
			?: throw IllegalStateException("GitHub profile fetch failed")
		val email = user.email?.takeIf { it.isNotBlank() } ?: fetchPrimaryEmail(accessToken)
		return GitHubProfile(
			id = user.id.toString(),
			login = user.login,
			name = user.name,
			email = email,
			avatarUrl = user.avatarUrl,
		)
	}

	private fun fetchPrimaryEmail(accessToken: String): String {
		val emails = restClient.get()
			.uri("https://api.github.com/user/emails")
			.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
			.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
			.retrieve()
			.body(Array<GitHubEmailResponse>::class.java)
			?.toList()
			.orEmpty()
		return emails.firstOrNull { it.primary && it.verified }?.email
			?: emails.firstOrNull { it.verified }?.email
			?: throw IllegalStateException("GitHub account has no verified email")
	}

	private fun callbackUrl(): String = "${authProperties.apiOrigin.trimEnd('/')}/api/auth/callback/github"

	@JsonIgnoreProperties(ignoreUnknown = true)
	private data class GitHubTokenResponse(
		@JsonProperty("access_token") val accessToken: String? = null,
		val error: String? = null,
	)

	@JsonIgnoreProperties(ignoreUnknown = true)
	private data class GitHubUserResponse(
		val id: Long,
		val login: String,
		val name: String? = null,
		val email: String? = null,
		@JsonProperty("avatar_url") val avatarUrl: String? = null,
	)

	@JsonIgnoreProperties(ignoreUnknown = true)
	private data class GitHubEmailResponse(
		val email: String,
		val primary: Boolean = false,
		val verified: Boolean = false,
	)
}
