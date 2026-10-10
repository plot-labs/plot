package com.plot.api.agent

import com.plot.api.chat.ChatRunProgressPersistence

/**
 * Streams one agent run's partial response text to its Chat while the model answers.
 *
 * Only Chat runs stream. Writes are throttled so a fast token stream does not
 * turn into one database write per token.
 */
internal class AgentChatProgressStream(
	private val claim: ClaimedAgentRun,
	private val enabled: Boolean,
	private val chatProgress: ChatRunProgressPersistence,
) {
	private var epoch: Long? = null
	private val text = StringBuilder()
	private var lastWriteNanos = 0L

	/** Opens a fresh progress epoch for the next model call. */
	fun start() {
		if (!enabled) return
		epoch = chatProgress.beginAgent(claim)
		text.setLength(0)
		lastWriteNanos = 0
	}

	fun append(delta: String) {
		val current = epoch ?: return
		require(text.length + delta.length <= MAX_PROGRESS_CHARACTERS)
		text.append(delta)
		val now = System.nanoTime()
		if (lastWriteNanos == 0L || now - lastWriteNanos >= WRITE_INTERVAL_NANOS) {
			chatProgress.updateAgent(claim, current, "RESPONDING", text.toString())
			lastWriteNanos = now
		}
	}

	/** Writes the given phase and text now, bypassing the throttle. */
	fun flush(phase: String = "RESPONDING", content: String = text.toString()) {
		epoch?.let { chatProgress.updateAgent(claim, it, phase, content) }
	}

	private companion object {
		const val MAX_PROGRESS_CHARACTERS = 40_000
		const val WRITE_INTERVAL_NANOS = 250_000_000L
	}
}
