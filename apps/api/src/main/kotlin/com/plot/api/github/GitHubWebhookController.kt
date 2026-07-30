package com.plot.api.github

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class GitHubWebhookController(
	private val verifier: GitHubWebhookVerifier,
	private val parser: GitHubWebhookParser,
	private val service: GitHubWebhookService,
) {
	@PostMapping("/api/github/webhook", consumes = ["application/json"])
	fun receive(
		@RequestHeader("X-GitHub-Delivery") deliveryId: String,
		@RequestHeader("X-GitHub-Event") eventType: String,
		@RequestHeader("X-Hub-Signature-256", required = false) signature: String?,
		@RequestBody rawBody: ByteArray,
	): ResponseEntity<Void> {
		verifier.verify(rawBody, signature)
		service.accept(parser.parse(deliveryId, eventType, rawBody))
		return ResponseEntity.accepted().build()
	}
}
