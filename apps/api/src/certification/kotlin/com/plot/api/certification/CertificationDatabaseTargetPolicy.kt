package com.plot.api.certification

import java.net.URI
import org.springframework.jdbc.core.JdbcTemplate

object CertificationDatabaseTargetPolicy {
	fun validate(
		jdbcUrl: String,
		databaseName: String,
		expectedFingerprint: String,
		disposableToken: String,
		jdbcTemplate: JdbcTemplate,
	) {
		if (!jdbcUrl.startsWith("jdbc:postgresql://") || !Regex("^plot_cert_[a-f0-9]{8,64}$").matches(databaseName) ||
			certificationDatabaseFingerprint(databaseName, disposableToken) != expectedFingerprint
		) reject()
		val uri = runCatching { URI(jdbcUrl.removePrefix("jdbc:")) }.getOrNull() ?: reject()
		if (uri.scheme != "postgresql" || uri.host !in setOf("127.0.0.1", "::1", "localhost") ||
			uri.port !in 1..65535 || uri.userInfo != null || uri.query != null || uri.fragment != null ||
			uri.path != "/$databaseName" || jdbcTemplate.queryForObject("select current_database()", String::class.java) != databaseName
		) reject()
	}

	private fun reject(): Nothing = throw CertificationDatabaseTargetException()
}

class CertificationDatabaseTargetException : IllegalStateException("CERTIFICATION_DATABASE_TARGET_REJECTED")
