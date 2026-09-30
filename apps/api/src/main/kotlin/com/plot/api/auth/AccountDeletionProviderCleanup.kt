package com.plot.api.auth

import com.plot.api.auth.workos.WorkOSOrganizationDeletionGateway
import com.plot.api.auth.workos.WorkOSUserDeletionGateway
import com.plot.api.common.WorkerTurnRecovery
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Retries provider deletion after local account access has been removed. */
@Service
class AccountDeletionProviderCleanup(
    private val persistence: AccountCleanupPersistence,
    private val userGateway: WorkOSUserDeletionGateway,
    private val organizationGateway: WorkOSOrganizationDeletionGateway,
    @Qualifier("accountCleanupTaskExecutor") taskExecutor: TaskExecutor,
    @Qualifier("accountCleanupRetryExecutor") retryExecutor: ScheduledExecutorService,
    @Value("\${plot.account-deletion.dispatch-enabled:true}") private val enabled: Boolean,
) {
    private val recovery = WorkerTurnRecovery(
        taskExecutor, retryExecutor, Clock.systemUTC(), Duration.ofMinutes(30),
        persistence::nextWakeupAt, ::dispatch,
    )

    /** Keep the existing initial synchronous attempt; arm durable follow-up after it. */
    fun cleanup(workOSUserId: String) {
        try {
            attemptCleanup(workOSUserId)
        } catch (failure: RuntimeException) {
            logger.warn("Initial account cleanup failed; durable recovery will retry", failure)
        } finally {
            dispatch()
        }
    }

    private fun attemptCleanup(userId: String) {
        val claim = persistence.claim(userId) ?: return
        try {
            userGateway.delete(claim.userId)
            persistence.organizations(claim).forEach { organization ->
                check(persistence.renew(claim)) { "Account cleanup lease lost" }
                organizationGateway.delete(organization)
            }
            check(persistence.complete(claim)) { "Account cleanup lease lost" }
        } catch (failure: RuntimeException) {
            logger.warn("Account provider cleanup will retry", failure)
            // A stale worker cannot overwrite the current owner's state.
            persistence.retry(claim, failure.javaClass.simpleName)
        }
    }

    fun dispatch() {
        recovery.dispatch {
            var due = persistence.dueUsers()
            while (due.isNotEmpty()) {
                due.forEach(::attemptCleanup)
                due = persistence.dueUsers()
            }
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverAtStartup() {
        if (enabled) dispatch()
    }

    @Scheduled(fixedDelayString = "\${plot.account-deletion.cleanup-delay:PT30M}", initialDelayString = "\${plot.account-deletion.cleanup-delay:PT30M}")
    fun retryPending() {
        if (enabled) dispatch()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AccountDeletionProviderCleanup::class.java)
    }
}
