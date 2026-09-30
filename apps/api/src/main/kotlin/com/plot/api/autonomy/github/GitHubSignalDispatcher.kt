package com.plot.api.autonomy.github

import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.common.WorkerTurnRecovery
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class GitHubSignalAccepted

/** Wake after commit; recover persisted retries and orphaned leases without idle polling. */
@Component
class GitHubSignalDispatcher(
	projection: GitHubSignalProjection,
	inbox: SignalInbox,
	@Qualifier("githubSignalTaskExecutor") taskExecutor: TaskExecutor,
	@Qualifier("githubWorkerRetryExecutor") retryExecutor: ScheduledExecutorService,
	@Value("\${plot.autonomy.dispatch-enabled:true}") private val enabled: Boolean,
) {
	private val recovery = WorkerTurnRecovery(
		taskExecutor, retryExecutor, Clock.systemUTC(), Duration.ofMinutes(30),
		{ inbox.nextWakeupAt("GITHUB") }, ::dispatch,
	)
	private val drain = projection::scan

	fun dispatch() {
		recovery.dispatch(drain)
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	fun onSignalAccepted(event: GitHubSignalAccepted) {
		if (enabled) dispatch()
	}

	@EventListener(ApplicationReadyEvent::class)
	fun recoverAtStartup() {
		if (enabled) dispatch()
	}

	@Scheduled(fixedDelayString = "\${plot.autonomy.scan-delay:PT30M}", initialDelayString = "\${plot.autonomy.scan-delay:PT30M}")
	fun recoverMissedWakeups() {
		if (enabled) dispatch()
	}
}
