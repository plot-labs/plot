package com.plot.api.common

import java.sql.Timestamp
import java.time.Instant

object JdbcTime {
	fun timestamp(value: Instant): Timestamp = Timestamp.from(value)
}
