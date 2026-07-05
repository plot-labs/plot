package com.plot.api.common

import java.time.Clock
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.springframework.stereotype.Component

@Component
class UuidGenerator(
	private val clock: Clock = Clock.systemUTC(),
) {
	@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
	fun next(): UUID {
		return Uuid.generateV7NonMonotonicAt(clock.instant().toKotlinInstant()).toJavaUuid()
	}
}
