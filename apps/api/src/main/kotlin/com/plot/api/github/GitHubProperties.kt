package com.plot.api.github

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.github")
data class GitHubProperties(
	val enabled: Boolean = false,
	val devOnly: Boolean = true,
	val loopbackOnly: Boolean = true,
	val appId: String? = null,
	val appSlug: String? = null,
	val privateKey: String? = null,
	val stateSecret: String? = null,
	val apiBaseUrl: String = "https://api.github.com",
	val webBaseUrl: String = "https://github.com",
	val stateTtlSeconds: Long = 900,
	val importPageCap: Int = 20,
	val repositoryPageCap: Int = 100,
)
