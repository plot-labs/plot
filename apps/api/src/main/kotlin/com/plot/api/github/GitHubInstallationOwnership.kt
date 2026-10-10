package com.plot.api.github

import com.plot.api.common.ApiException
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** The product-owned GitHub credential a Plot user linked, as needed for ownership checks. */
data class LinkedGitHubAccount(val githubAccountId: Long, val accessToken: String?)

/**
 * Decides which GitHub App installations a Plot user may attach to a workspace.
 *
 * Every rule here is answered by GitHub and the user's linked credential, with
 * no workspace persistence. An installation ID can arrive from the browser, so
 * nothing is connected until the linked identity is shown to control it.
 */
@Component
class GitHubInstallationOwnership(
	private val properties: GitHubProperties,
	private val githubClient: GitHubClient,
	private val productCredentialRepository: GitHubProductCredentialRepository,
) {
	fun requireLinkedAccount(userId: UUID): LinkedGitHubAccount =
		findLinkedAccount(userId)?.takeIf { !it.accessToken.isNullOrBlank() }
			?: throw ApiException(
				HttpStatus.BAD_REQUEST,
				"GITHUB_ACCOUNT_NOT_LINKED",
				"No linked GitHub account was found; connect a GitHub account and retry",
			)

	/** Lists this app's installations the linked account can reach. */
	fun accessibleInstallations(link: LinkedGitHubAccount): List<GitHubUserInstallation> {
		val appId = properties.appId?.takeIf { it.isNotBlank() }
			?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED", "GitHub is not configured")
		return try {
			githubClient.listUserInstallations(requireNotNull(link.accessToken)).filter { it.appId == appId }
		} catch (exception: ApiException) {
			if (exception.error != "GITHUB_ACCESS_DENIED") throw exception
			// A separately issued OAuth token can identify the user but GitHub may
			// reject it for /user/installations. App credentials can still safely
			// recover a personal installation by matching the linked account ID.
			githubClient.listAppInstallations().filter {
				it.appId == appId &&
					it.accountType.equals("USER", ignoreCase = true) &&
					it.accountId == link.githubAccountId
			}.ifEmpty { throw exception }
		}
	}

	fun installationNotFound() = ApiException(
		HttpStatus.NOT_FOUND,
		"GITHUB_INSTALLATION_NOT_FOUND",
		"No Plot GitHub App installation was found for your account",
	)

	/**
	 * Picks the one installation a sync should attach: the single organization
	 * the user administers, else their personal installation, else the only one.
	 */
	fun selectAccessibleInstallation(installations: List<GitHubUserInstallation>, link: LinkedGitHubAccount): Long {
		val personalInstall = installations.firstOrNull {
			it.accountType.equals("USER", ignoreCase = true) && it.accountId == link.githubAccountId
		}
		val adminOrgs = installations.filter { it.accountType.equals("ORGANIZATION", ignoreCase = true) }
			.filter { isOrganizationAdmin(link.accessToken, it.accountLogin) }
		if (adminOrgs.size == 1) return adminOrgs.first().installationId
		if (adminOrgs.size > 1) throw installationAmbiguous()
		if (personalInstall != null) return personalInstall.installationId
		if (installations.size == 1) return installations.first().installationId
		throw installationAmbiguous()
	}

	/**
	 * The callback only proves the state belongs to this workspace owner; the
	 * installation ID arrives from the browser and could name anyone's install.
	 * Prove the caller's linked GitHub identity controls the installation before
	 * any token or connection is issued.
	 */
	fun verifyOwnership(userId: UUID, installationId: Long) {
		val link = findLinkedAccount(userId)
			?: throw installationNotOwned("No linked GitHub account was found; connect a GitHub account and retry")
		val installation = try {
			githubClient.getInstallation(installationId)
		} catch (exception: ApiException) {
			if (exception.error == "GITHUB_NOT_FOUND") throw installationNotOwned()
			throw exception
		}
		val owned = when (installation.account.type.uppercase()) {
			"USER" -> installation.account.id == link.githubAccountId && link.githubAccountId > 0L
			"ORGANIZATION" -> isOrganizationAdmin(link.accessToken, installation.account.login)
			else -> false
		}
		if (!owned) throw installationNotOwned()
	}

	private fun isOrganizationAdmin(accessToken: String?, orgLogin: String): Boolean {
		val token = accessToken?.takeIf { it.isNotBlank() } ?: return false
		val identity = githubClient.resolveAuthenticatedUser(token)
		return try {
			githubClient.organizationMembershipRole(token, orgLogin, identity.login) == "admin"
		} catch (exception: ApiException) {
			if (exception.error == "GITHUB_ACCESS_DENIED") {
				throw ApiException(
					HttpStatus.UNAUTHORIZED,
					"GITHUB_REAUTH_REQUIRED",
					"GitHub authorization must be refreshed to check organization membership; connect GitHub again",
				)
			}
			throw exception
		}
	}

	private fun findLinkedAccount(userId: UUID): LinkedGitHubAccount? = productCredentialRepository
		.findActiveByUserId(userId)
		?.let { credential ->
			LinkedGitHubAccount(
				githubAccountId = credential.githubAccountId,
				accessToken = credential.accessToken,
			)
		}

	private fun installationAmbiguous() = ApiException(
		HttpStatus.CONFLICT,
		"GITHUB_INSTALLATION_AMBIGUOUS",
		"Multiple Plot GitHub App installations were found; reconnect from GitHub settings for the account you want to use",
	)

	private fun installationNotOwned(message: String = "GitHub installation does not belong to the authenticated user") =
		ApiException(HttpStatus.FORBIDDEN, "GITHUB_INSTALLATION_NOT_OWNED", message)
}
