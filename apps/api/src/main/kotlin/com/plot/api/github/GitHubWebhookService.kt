package com.plot.api.github

import com.plot.api.observability.stopSafely
import com.plot.api.routine.GitHubChangeRoutineService
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class GitHubWebhookService(
	private val properties: GitHubProperties,
	private val scopeResolver: GitHubReleaseScopeResolver,
	private val persistence: GitHubReleasePersistence,
	private val dispatcher: GitHubReleaseDraftDispatcher,
	private val gitHubChangeRoutineService: GitHubChangeRoutineService,
	private val lifecycleService: GitHubSourceAccessLifecycleService,
	private val observationRegistry: ObservationRegistry,
) {
	@Transactional
	fun accept(webhook: ParsedGitHubWebhook): GitHubWebhookDelivery {
		val observation = Observation.start("plot.github.webhook", observationRegistry)
			.highCardinalityKeyValue("plot.webhook_delivery_id", webhook.externalDeliveryId)
		try {
			observation.openScope().use {
				val delivery = acceptInternal(webhook)
				observation.lowCardinalityKeyValue("plot.disposition", delivery.disposition.name)
				return delivery
			}
		} catch (failure: RuntimeException) {
			observation.lowCardinalityKeyValue("plot.disposition", "FAILED")
			throw failure
		} finally {
			observation.stopSafely()
		}
	}

	private fun acceptInternal(webhook: ParsedGitHubWebhook): GitHubWebhookDelivery {
		persistence.findDelivery(webhook.externalDeliveryId)?.let { return it }
		val delivery = GitHubWebhookDelivery(
			id = UUID.randomUUID(),
			externalDeliveryId = webhook.externalDeliveryId,
			eventType = webhook.eventType,
			eventAction = webhook.eventAction,
			installationId = webhook.installationId,
			repositoryId = webhook.repositoryId,
			ref = webhook.ref,
			beforeSha = webhook.beforeSha,
			afterSha = webhook.afterSha,
			tagName = webhook.tagName,
			refCreated = webhook.refCreated,
			refDeleted = webhook.refDeleted,
			forced = webhook.forced,
			payloadHash = webhook.payloadHash,
			disposition = GitHubWebhookDisposition.RECEIVED,
			errorCode = null,
			receivedAt = Instant.now(),
			processedAt = null,
		)
		val inserted = persistence.insertDelivery(delivery)
		if (inserted.id != delivery.id) return inserted

		if (lifecycleService.isLifecycle(webhook)) {
			val projection = lifecycleService.project(webhook)
			return mark(delivery, projection.disposition)
		}

		val context = webhook.installationId?.let { installationId ->
			webhook.repositoryId?.let { repositoryId -> scopeResolver.resolve(installationId, repositoryId) }
		}
		if (context == null) return mark(delivery, GitHubWebhookDisposition.IGNORED)

		return when {
			webhook.eventType == "push" && (webhook.refDeleted == true || webhook.forced == true) ->
				mark(delivery, GitHubWebhookDisposition.IGNORED)
			webhook.eventType == "push" && webhook.tagName != null -> {
				val queued = gitHubChangeRoutineService.accept(context, delivery, webhook)
				if (gitHubChangeRoutineService.hasReleaseEventRoutines(context)) {
					mark(delivery, if (queued > 0) GitHubWebhookDisposition.QUEUED else GitHubWebhookDisposition.OBSERVED)
				} else {
					enqueue(context, delivery, webhook.tagName, webhook.afterSha)
				}
			}
			webhook.eventType == "push" && webhook.ref == "refs/heads/${context.defaultBranch}" -> {
				val queued = gitHubChangeRoutineService.accept(context, delivery, webhook)
				mark(delivery, if (queued > 0) GitHubWebhookDisposition.QUEUED else GitHubWebhookDisposition.OBSERVED)
			}
			webhook.eventType == "push" -> mark(delivery, GitHubWebhookDisposition.IGNORED)
			// release.target_commitish may be a mutable branch. Only a canonical tag push
			// contributes an immutable observed head SHA to the release request.
			webhook.eventType == "release" && webhook.eventAction == "published" && webhook.tagName != null -> {
				val queued = gitHubChangeRoutineService.accept(context, delivery, webhook)
				if (gitHubChangeRoutineService.hasReleaseEventRoutines(context)) {
					mark(delivery, if (queued > 0) GitHubWebhookDisposition.QUEUED else GitHubWebhookDisposition.OBSERVED)
				} else {
					enqueue(context, delivery, webhook.tagName, observedHeadSha = null)
				}
			}
			else -> mark(delivery, GitHubWebhookDisposition.IGNORED)
		}
	}

	private fun enqueue(
		context: GitHubReleaseSourceContext,
		delivery: GitHubWebhookDelivery,
		tagName: String,
		observedHeadSha: String?,
	): GitHubWebhookDelivery {
		persistence.enqueueRelease(
			context.workspaceId,
			context.sourceScopeId,
			delivery.id,
			tagName,
			observedHeadSha,
		)
		persistence.markDelivery(delivery.id, GitHubWebhookDisposition.QUEUED)
		if (properties.releaseAutomationEnabled) scheduleDispatchAfterCommit()
		return delivery.copy(disposition = GitHubWebhookDisposition.QUEUED)
	}

	private fun scheduleDispatchAfterCommit() {
		check(
			TransactionSynchronizationManager.isSynchronizationActive() &&
				TransactionSynchronizationManager.isActualTransactionActive(),
		) { "GitHub release dispatch requires an active transaction" }
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() {
				dispatcher.dispatch()
			}
		})
	}

	private fun mark(delivery: GitHubWebhookDelivery, disposition: GitHubWebhookDisposition): GitHubWebhookDelivery {
		persistence.markDelivery(delivery.id, disposition)
		return delivery.copy(disposition = disposition)
	}
}
