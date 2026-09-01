package com.plot.api.github

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class GitHubWorkerStartupRecovery(
	private val properties: GitHubProperties,
	private val accessCheckDispatcher: GitHubRepositoryAccessCheckDispatcher,
	private val monitoringDispatcher: GitHubRepositoryMonitoringDispatcher,
	private val releaseDispatcher: GitHubReleaseDraftDispatcher,
) {
	@EventListener(ApplicationReadyEvent::class)
	fun drainOrphanedQueueRows() {
		if (properties.enabled) {
			accessCheckDispatcher.dispatch()
			monitoringDispatcher.dispatch()
		}
		if (properties.releaseAutomationEnabled) {
			releaseDispatcher.dispatch()
		}
	}
}
