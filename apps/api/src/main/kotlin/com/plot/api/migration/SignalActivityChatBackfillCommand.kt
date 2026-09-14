package com.plot.api.migration

import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@ConfigurationProperties("plot.signal-activity-chat-backfill")
data class SignalActivityChatBackfillProperties(
	val enabled: Boolean = false,
	val checkpointKey: String = "signal_activity_chat_backfill_v2",
	val batchSize: Int = 100,
	val maxBatches: Int = 100,
	val leaseDuration: Duration = Duration.ofMinutes(2),
) {
	init {
		require(batchSize in 1..500) { "plot.signal-activity-chat-backfill.batch-size must be between 1 and 500" }
		require(maxBatches in 1..10_000) { "plot.signal-activity-chat-backfill.max-batches must be between 1 and 10000" }
		require(!leaseDuration.isNegative && !leaseDuration.isZero) {
			"plot.signal-activity-chat-backfill.lease-duration must be positive"
		}
	}
}

/**
 * A deliberately double-gated operator command. Ordinary API startup cannot
 * run historical writes: an operator must select this profile and explicitly
 * enable the command for a bounded invocation.
 */
@Component
@Profile("signal-activity-chat-backfill")
@ConditionalOnProperty(
	prefix = "plot.signal-activity-chat-backfill",
	name = ["enabled"],
	havingValue = "true",
	matchIfMissing = false,
)
class SignalActivityChatBackfillCommand(
	private val service: SignalActivityChatBackfillService,
	private val properties: SignalActivityChatBackfillProperties,
) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val report = service.run(
			checkpointKey = properties.checkpointKey,
			requestedBatchSize = properties.batchSize,
			maxBatches = properties.maxBatches,
			leaseDuration = properties.leaseDuration,
		)
		logger.info(
			"Signal Activity Chat backfill checkpoint={} status={} leaseAcquired={} attempted={} inserted={} skipped={} lag={}",
			report.checkpointKey,
			report.status,
			report.leaseAcquired,
			report.attemptedCount,
			report.insertedCount,
			report.skippedCount,
			report.lag?.totalLag,
		)
	}

	private companion object {
		val logger = LoggerFactory.getLogger(SignalActivityChatBackfillCommand::class.java)
	}
}
