package com.plot.api.auth.web

import com.plot.api.auth.PlotAuthProperties
import com.plot.api.auth.jwt.PlotJwtService
import com.plot.api.auth.oauth.AuthOAuthService
import com.plot.api.auth.oauth.GitHubOAuthService
import com.plot.api.auth.oauth.OAuthStateCodec
import com.plot.api.auth.password.PasswordAuthenticationService
import com.plot.api.auth.policy.AllowedEmailPolicy
import com.plot.api.auth.session.AuthSessionService
import com.plot.api.auth.session.AuthenticatedSession
import com.plot.api.auth.session.SessionAuthenticationToken
import com.plot.api.common.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

@RestController
@RequestMapping("/api/auth")
class AuthController(
	private val authProperties: PlotAuthProperties,
	private val githubOAuthService: GitHubOAuthService,
	private val oauthStateCodec: OAuthStateCodec,
	private val authOAuthService: AuthOAuthService,
	private val passwordAuthenticationService: PasswordAuthenticationService,
	private val authSessionService: AuthSessionService,
	private val plotJwtService: PlotJwtService,
	private val allowedEmailPolicy: AllowedEmailPolicy,
) {
	@GetMapping("/sign-in/github")
	fun signInGitHub(
		@RequestParam(name = "callbackURL", required = false) callbackUrl: String?,
	): RedirectView {
		val redirectPath = callbackUrl ?: "/auth/complete"
		val state = oauthStateCodec.encode(redirectPath)
		return RedirectView(githubOAuthService.authorizationUrl(state))
	}

	@PostMapping("/sign-in/password")
	fun signInPassword(
		@Valid @RequestBody request: PasswordSignInRequest,
		httpRequest: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<AuthSessionResponse> {
		val authenticated = passwordAuthenticationService.authenticate(request.email, request.password, httpRequest)
		authSessionService.writeSessionCookie(response, authenticated.session.token)
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(toSessionResponse(authenticated))
	}

	@PostMapping("/sign-up/password")
	fun signUpPassword(
		@Valid @RequestBody request: PasswordSignUpRequest,
		httpRequest: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<AuthSessionResponse> {
		val authenticated = passwordAuthenticationService.register(
			email = request.email,
			password = request.password,
			name = null,
			request = httpRequest,
		)
		authSessionService.writeSessionCookie(response, authenticated.session.token)
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(toSessionResponse(authenticated))
	}

	@GetMapping("/callback/github")
	fun callbackGitHub(
		@RequestParam code: String,
		@RequestParam state: String,
		request: HttpServletRequest,
		response: HttpServletResponse,
	): RedirectView = try {
		val redirectPath = oauthStateCodec.decode(state)
		authOAuthService.completeGitHubLogin(code, request, response)
		RedirectView("${authProperties.appOrigin.trimEnd('/')}$redirectPath")
	} catch (_: AllowedEmailPolicy.AccessDeniedException) {
		RedirectView("${authProperties.appOrigin.trimEnd('/')}/sign-in?error=access_denied")
	} catch (_: Exception) {
		RedirectView("${authProperties.appOrigin.trimEnd('/')}/sign-in?error=oauth_failed")
	}

	@GetMapping("/session")
	fun session(authentication: Authentication?): ResponseEntity<AuthSessionResponse> {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(toSessionResponse(authenticatedSession(authentication)))
	}

	private fun toSessionResponse(authenticated: AuthenticatedSession): AuthSessionResponse {
		val user = authenticated.user
		return AuthSessionResponse(
			user = AuthUserResponse(
				id = user.id,
				email = user.email,
				name = user.name,
				image = user.image,
			),
			session = AuthSessionMetaResponse(
				id = authenticated.session.id,
				expiresAt = authenticated.session.expiresAt.toString(),
			),
		)
	}

	@GetMapping("/token")
	fun token(authentication: Authentication?): ResponseEntity<AuthTokenResponse> {
		val user = authenticatedSession(authentication).user
		if (!allowedEmailPolicy.isAllowed(user.email)) {
			throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")
		}
		val jwt = plotJwtService.mintToken(user)
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(AuthTokenResponse(token = jwt))
	}

	@PostMapping("/sign-out")
	fun signOut(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
		authSessionService.revokeSession(request)
		authSessionService.clearSessionCookie(response)
		return ResponseEntity.noContent()
			.cacheControl(CacheControl.noStore())
			.build()
	}

	@GetMapping("/jwks")
	fun jwks(): ResponseEntity<Map<String, Any>> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(plotJwtService.publicJwks())

	private fun authenticatedSession(authentication: Authentication?) = when (authentication) {
		is SessionAuthenticationToken -> authentication.authenticatedSession
		else -> throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")
	}
}

data class AuthSessionResponse(
	val user: AuthUserResponse,
	val session: AuthSessionMetaResponse,
)

data class AuthUserResponse(
	val id: String,
	val email: String,
	val name: String,
	val image: String?,
)

data class AuthSessionMetaResponse(
	val id: String,
	val expiresAt: String,
)

data class AuthTokenResponse(
	val token: String,
)

data class PasswordSignInRequest(
	@field:NotBlank(message = "Email is required")
	@field:Email(message = "Enter a valid email address")
	@field:Size(max = 320, message = "Email must be at most 320 characters")
	val email: String,
	@field:NotBlank(message = "Password is required")
	@field:Size(max = 128, message = "Password must be at most 128 characters")
	val password: String,
)

data class PasswordSignUpRequest(
	@field:NotBlank(message = "Email is required")
	@field:Email(message = "Enter a valid email address")
	@field:Size(max = 320, message = "Email must be at most 320 characters")
	val email: String,
	@field:NotBlank(message = "Password is required")
	@field:Size(min = 10, max = 128, message = "Password must be between 10 and 128 characters")
	val password: String,
)
