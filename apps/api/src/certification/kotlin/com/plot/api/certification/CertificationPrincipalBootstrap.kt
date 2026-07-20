package com.plot.api.certification

import com.plot.api.common.JdbcTime.timestamp
import com.plot.api.dev.DevContext
import java.time.Clock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class CertificationPrincipalBootstrap(
	private val jdbcTemplate: JdbcTemplate,
	private val devContext: DevContext = DevContext(),
	private val clock: Clock = Clock.systemUTC(),
) {
	fun bootstrap(campaignId: String, requireEmptyBaseline: Boolean = true) {
		if (!Regex("^campaign-[a-f0-9]{16,64}$").matches(campaignId)) throw CertificationPrincipalBootstrapException()
		TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbcTemplate.dataSource))).executeWithoutResult {
		if (requireEmptyBaseline) {
			val count = listOf("users", "workspaces", "workspace_members", "writing_blocks", "generation_runs").sumOf { table ->
				jdbcTemplate.queryForObject("select count(*) from $table", Long::class.java) ?: 0L
			}
			if (count != 0L) throw CertificationPrincipalBootstrapException()
		}
		val now = timestamp(clock.instant())
		jdbcTemplate.update(
			"insert into users (id, email, display_name, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?) on conflict (id) do nothing",
			devContext.devUserId, "certification@invalid.example", "Certification", now, now,
		)
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, ?, ?, ?, 'ACTIVE', ?, ?) on conflict (id) do nothing",
			devContext.devWorkspaceId, "Certification", "cert-${sha256(campaignId).removePrefix("sha256:").take(24)}",
			devContext.devUserId, now, now,
		)
		jdbcTemplate.update(
			"insert into workspace_members (id, workspace_id, user_id, role, status, joined_at, created_at, updated_at) values (?, ?, ?, 'OWNER', 'ACTIVE', ?, ?, ?) on conflict (workspace_id, user_id) do nothing",
			devContext.devWorkspaceMemberId, devContext.devWorkspaceId, devContext.devUserId, now, now, now,
		)
		}
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			if (args.isNotEmpty()) throw CertificationPrincipalBootstrapException()
			val env = System.getenv()
			val forbidden = setOf(
				"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
				"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
			)
			if (forbidden.any(env::containsKey)) throw CertificationPrincipalBootstrapException()
			fun required(name: String) = env[name]?.takeIf(String::isNotBlank) ?: throw CertificationPrincipalBootstrapException()
			val databaseName = required("PLOT_CERTIFICATION_DATABASE_NAME")
			val fingerprint = certificationDatabaseFingerprint(databaseName, required("PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"))
			if (fingerprint != required("PLOT_CERTIFICATION_DATABASE_FINGERPRINT")) throw CertificationPrincipalBootstrapException()
			val jdbcUrl = required("PLOT_CERTIFICATION_DATABASE_URL")
			val jdbc = JdbcTemplate(DriverManagerDataSource(
				jdbcUrl,
				required("PLOT_CERTIFICATION_DATABASE_USERNAME"),
				required("PLOT_CERTIFICATION_DATABASE_PASSWORD"),
			))
			CertificationDatabaseTargetPolicy.validate(jdbcUrl, databaseName, fingerprint, required("PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN"), jdbc)
			CertificationPrincipalBootstrap(jdbc).bootstrap(required("PLOT_CERTIFICATION_CAMPAIGN_ID"))
		}
	}
}

class CertificationPrincipalBootstrapException : IllegalStateException("CERTIFICATION_PRINCIPAL_BOOTSTRAP_REJECTED")
