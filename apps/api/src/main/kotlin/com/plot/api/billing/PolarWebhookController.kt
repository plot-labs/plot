package com.plot.api.billing

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/polar/webhook")
class PolarWebhookController(
	private val verifier: PolarWebhookVerifier,
	private val subscriptionService: PolarSubscriptionService,
) {
	@PostMapping
	fun receive(
		@RequestHeader("webhook-id") webhookId: String,
		@RequestHeader("webhook-timestamp") webhookTimestamp: String,
		@RequestHeader("webhook-signature") webhookSignature: String,
		@RequestBody rawBody: String,
	): ResponseEntity<Void> {
		verifier.verify(webhookId, webhookTimestamp, webhookSignature, rawBody)
		subscriptionService.handle(webhookId, rawBody)
		return ResponseEntity.noContent().build()
	}
}
