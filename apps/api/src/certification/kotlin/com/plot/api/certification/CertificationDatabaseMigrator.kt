package com.plot.api.certification

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/** Migrates only an attested disposable certification database, without starting an HTTP or model runtime. */
object CertificationDatabaseMigrator {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isNotEmpty() || PROVIDER_CREDENTIALS.any(System.getenv()::containsKey)) reject()
		fun required(name: String) = System.getenv(name)?.takeIf(String::isNotBlank) ?: reject()
		val url = required("PLOT_CERTIFICATION_DATABASE_URL")
		val username = required("PLOT_CERTIFICATION_DATABASE_USERNAME")
		val password = required("PLOT_CERTIFICATION_DATABASE_PASSWORD")
		val dataSource = DriverManagerDataSource(url, username, password)
		val jdbc = JdbcTemplate(dataSource)
		CertificationDatabaseTargetPolicy.validate(
			url,
			required("PLOT_CERTIFICATION_DATABASE_NAME"),
			required("PLOT_CERTIFICATION_DATABASE_FINGERPRINT"),
			required("PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"),
			jdbc,
		)
		val flyway = Flyway.configure().dataSource(dataSource).load()
		if (flyway.info().applied().isNotEmpty()) reject()
		if (flyway.migrate().migrationsExecuted <= 0) reject()
		val expectedTables = jdbc.queryForObject(
			"""
			select count(*)::int from information_schema.tables
			where table_schema = 'public' and table_name in ('users', 'workspaces', 'generation_runs')
			""".trimIndent(),
			Int::class.java,
		) ?: 0
		if (expectedTables != 3) reject()
	}

	private fun reject(): Nothing = throw CertificationDatabaseMigrationException()

	private val PROVIDER_CREDENTIALS = setOf(
		"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
		"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
	)
}

class CertificationDatabaseMigrationException : IllegalStateException("CERTIFICATION_DATABASE_MIGRATION_REJECTED")
