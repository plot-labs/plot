import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

plugins {
	kotlin("jvm") version "2.4.0"
	kotlin("plugin.spring") version "2.4.0"
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.4.0"
}

group = "com.plot"
version = "0.0.1-SNAPSHOT"
description = "Plot API"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.0"
extra["kotlin.version"] = "2.4.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
	implementation("org.springframework.ai:spring-ai-starter-mcp-client")
	implementation("org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
	implementation("org.springframework.ai:spring-ai-vector-store-advisor")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.add("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

val certification by sourceSets.creating

certification.compileClasspath += sourceSets.main.get().output
certification.runtimeClasspath += sourceSets.main.get().output

configurations[certification.implementationConfigurationName]
	.extendsFrom(configurations["implementation"])
configurations[certification.runtimeOnlyConfigurationName]
	.extendsFrom(configurations["runtimeOnly"])

sourceSets.test {
	compileClasspath += certification.output
	runtimeClasspath += certification.output
}

tasks.named("compileTestKotlin") {
	dependsOn(tasks.named("compileCertificationKotlin"))
}

tasks.named("compileCertificationKotlin") {
	dependsOn(tasks.named("compileKotlin"))
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.named<Test>("test") {
	useJUnitPlatform { excludeTags("generation-certification-live") }
}

val generationCertificationPreflight by tasks.registering(Test::class) {
	description = "Runs the explicit OpenRouter route and attribution preflight"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("generation-certification-live") }
	filter { includeTestsMatching("com.plot.api.certification.OpenRouterCertificationLivePreflightTest") }
	setEnvironment(System.getenv().filterKeys { name ->
		name in setOf(
			"PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ",
			"OPENROUTER_API_KEY", "PLOT_GENERATION_CERTIFICATION_PREFLIGHT", "PLOT_AI_MODEL",
			"PLOT_AI_ROUTING_PROVIDER",
		)
	})
	doFirst {
		require(System.getenv("PLOT_GENERATION_CERTIFICATION_PREFLIGHT").equals("true", ignoreCase = true)) {
			"Set PLOT_GENERATION_CERTIFICATION_PREFLIGHT=true to opt in"
		}
		listOf("OPENROUTER_API_KEY", "PLOT_AI_MODEL", "PLOT_AI_ROUTING_PROVIDER").forEach { name ->
			require(!System.getenv(name).isNullOrBlank()) { "$name is required" }
		}
	}
}

val generationContractSmoke by tasks.registering(Test::class) {
	description = "Runs the explicit preflight and scored two-model OpenRouter contract matrix"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("generation-certification-live") }
	filter { includeTestsMatching("com.plot.api.ai.provider.OpenAiGenerationContractSmokeTest") }
	setEnvironment(System.getenv().filterKeys { name ->
		name in setOf(
			"PATH",
			"JAVA_HOME",
			"HOME",
			"TMPDIR",
			"LANG",
			"LC_ALL",
			"TZ",
			"OPENROUTER_API_KEY",
			"PLOT_AI_CONTRACT_SMOKE",
			"PLOT_AI_ROUTING_PROVIDER",
			"PLOT_GENERATION_SOURCE_REVISION",
			"PLOT_CERTIFICATION_OUTPUT_ROOT",
			"PLOT_CERTIFICATION_CAMPAIGN_ID",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH",
			"PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH",
			"PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT",
			"PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT",
			"PLOT_CERTIFICATION_REPLACEMENT_MODEL",
			"PLOT_CERTIFICATION_REPLACEMENT_ORDINAL",
			"PLOT_CERTIFICATION_REPLACEMENT_ATTEMPT_ID",
			"PLOT_CERTIFICATION_REPLACEMENT_TRIGGER_ATTEMPT_ID",
			"PLOT_CERTIFICATION_REPLACEMENT_RESULT_OUTPUT",
			"PLOT_AI_TIMEOUT_SECONDS",
			"PLOT_AI_MAX_OUTPUT_TOKENS",
			"PLOT_AI_TRANSPORT_RETRIES",
			"PLOT_AI_SCHEMA_RETRIES",
		)
	})
	doFirst {
		require(System.getenv("PLOT_AI_CONTRACT_SMOKE").equals("true", ignoreCase = true)) {
			"Set PLOT_AI_CONTRACT_SMOKE=true to opt in"
		}
		val replacement = !System.getenv("PLOT_CERTIFICATION_REPLACEMENT_MODEL").isNullOrBlank()
		listOf(
			"OPENROUTER_API_KEY", "PLOT_AI_ROUTING_PROVIDER", "PLOT_GENERATION_SOURCE_REVISION",
			"PLOT_CERTIFICATION_OUTPUT_ROOT", "PLOT_CERTIFICATION_CAMPAIGN_ID", "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH", "PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH",
			"PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT", "PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT",
		).forEach { name ->
			require(!System.getenv(name).isNullOrBlank()) { "$name is required" }
		}
		if (replacement) {
			listOf(
				"PLOT_CERTIFICATION_REPLACEMENT_ORDINAL", "PLOT_CERTIFICATION_REPLACEMENT_ATTEMPT_ID",
				"PLOT_CERTIFICATION_REPLACEMENT_TRIGGER_ATTEMPT_ID",
				"PLOT_CERTIFICATION_REPLACEMENT_RESULT_OUTPUT",
			).forEach { name -> require(!System.getenv(name).isNullOrBlank()) { "$name is required in replacement mode" } }
		}
	}
}

val generationCertificationDeterministic by tasks.registering(Test::class) {
	description = "Runs deterministic generation, citation, recovery, and export certification gates"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { excludeTags("generation-certification-live") }
	setEnvironment(System.getenv().filterKeys { name ->
		name in setOf(
			"PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ", "CI",
			"DOCKER_HOST", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "TESTCONTAINERS_HOST_OVERRIDE",
		)
	})
	filter {
		includeTestsMatching("com.plot.api.certification.DeterministicGenerationCertificationTest")
		includeTestsMatching("com.plot.api.generation.GenerationRunRecoveryIntegrationTest")
		includeTestsMatching("com.plot.api.contentpack.MarkdownExportServiceTest")
	}
	doLast {
		System.getenv("PLOT_CERTIFICATION_DETERMINISTIC_RESULT")?.takeIf(String::isNotBlank)?.let { value ->
			val revision = requireNotNull(System.getenv("PLOT_GENERATION_SOURCE_REVISION")?.takeIf(String::isNotBlank))
			val campaignId = requireNotNull(System.getenv("PLOT_CERTIFICATION_CAMPAIGN_ID")?.takeIf(String::isNotBlank))
			val manifestHash = requireNotNull(System.getenv("PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH")?.takeIf(String::isNotBlank))
			val corpusHash = requireNotNull(System.getenv("PLOT_CERTIFICATION_CORPUS_HASH")?.takeIf(String::isNotBlank))
			val profileHash = requireNotNull(System.getenv("PLOT_CERTIFICATION_PROFILE_HASH")?.takeIf(String::isNotBlank))
			val target = Path.of(value)
			require(target.isAbsolute && !Files.exists(target))
			Files.createDirectories(target.parent)
			require(!Files.isSymbolicLink(target.parent))
			val payload = """{"schemaVersion":"certification-deterministic-result-v1","sourceRevision":"$revision","campaignId":"$campaignId","campaignManifestHash":"$manifestHash","corpusHash":"$corpusHash","profileHash":"$profileHash","outcome":"PASS"}"""
			Files.writeString(
				target, payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
			)
		}
	}
}

fun JavaExec.certificationTool(mainClassName: String, allowedEnvironment: Set<String>) {
	group = "verification"
	dependsOn(tasks.named("compileCertificationKotlin"))
	mainClass.set(mainClassName)
	classpath = certification.runtimeClasspath
	setEnvironment(System.getenv().filterKeys(allowedEnvironment::contains))
	doFirst {
		require(System.getenv("PLOT_GENERATION_CERTIFICATION_TOOL").equals("true", ignoreCase = true)) {
			"Set PLOT_GENERATION_CERTIFICATION_TOOL=true to opt in"
		}
	}
}

val commonCertificationToolEnvironment = setOf("PLOT_GENERATION_CERTIFICATION_TOOL")

tasks.register<JavaExec>("generationCertificationAudit") {
	description = "Extracts one redacted persisted-audit envelope from a disposable certification database"
	certificationTool(
		"com.plot.api.certification.CertificationAuditExtractor",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_NAME", "PLOT_CERTIFICATION_DATABASE_USERNAME",
			"PLOT_CERTIFICATION_DATABASE_PASSWORD", "PLOT_CERTIFICATION_DATABASE_FINGERPRINT",
			"PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN", "PLOT_CERTIFICATION_CAMPAIGN_ID",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH", "PLOT_CERTIFICATION_MODEL_EXECUTION_ID",
			"PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH", "PLOT_CERTIFICATION_ATTEMPT_ID",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST", "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST", "OPENROUTER_API_KEY",
			"PLOT_CERTIFICATION_SCENARIO_ID", "PLOT_CERTIFICATION_ATTEMPT_ORDINAL", "PLOT_CERTIFICATION_WORKSPACE_ID",
			"PLOT_CERTIFICATION_RUN_ID", "PLOT_CERTIFICATION_IDEMPOTENCY_KEY",
			"PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH", "PLOT_CERTIFICATION_OUTPUT_ROOT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationPrincipal") {
	description = "Seeds the minimal certification principal after an empty disposable-database baseline"
	certificationTool(
		"com.plot.api.certification.CertificationPrincipalBootstrap",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_NAME", "PLOT_CERTIFICATION_DATABASE_USERNAME",
			"PLOT_CERTIFICATION_DATABASE_PASSWORD", "PLOT_CERTIFICATION_DATABASE_FINGERPRINT",
			"PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN", "PLOT_CERTIFICATION_CAMPAIGN_ID",
		),
	)
}

tasks.register<JavaExec>("generationCertificationMigrate") {
	description = "Migrates one empty disposable certification database without starting provider or HTTP runtimes"
	certificationTool(
		"com.plot.api.certification.CertificationDatabaseMigrator",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_NAME", "PLOT_CERTIFICATION_DATABASE_USERNAME",
			"PLOT_CERTIFICATION_DATABASE_PASSWORD", "PLOT_CERTIFICATION_DATABASE_FINGERPRINT",
			"PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN",
		),
	)
}

tasks.register<JavaExec>("generationCertificationImportedSourcePreflight") {
	description = "Scans the approved imported GitHub block set before any model call"
	certificationTool(
		"com.plot.api.certification.CertificationImportedSourcePreflight",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_USERNAME", "PLOT_CERTIFICATION_DATABASE_PASSWORD",
			"PLOT_CERTIFICATION_DATABASE_NAME", "PLOT_CERTIFICATION_DATABASE_FINGERPRINT", "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN",
			"PLOT_CERTIFICATION_SOURCE_ALIAS", "PLOT_CERTIFICATION_SOURCE_WINDOW_START", "PLOT_CERTIFICATION_SOURCE_WINDOW_END",
			"PLOT_CERTIFICATION_SOURCE_APPROVAL", "PLOT_CERTIFICATION_STARTED_AT", "PLOT_CERTIFICATION_WRITING_BLOCK_IDS",
			"PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationRestartRequest") {
	description = "Builds one fixed restart request from the post-import Writing Block set"
	certificationTool(
		"com.plot.api.certification.CertificationRestartRequestBuilder",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_USERNAME", "PLOT_CERTIFICATION_DATABASE_PASSWORD",
			"PLOT_CERTIFICATION_DATABASE_NAME", "PLOT_CERTIFICATION_DATABASE_FINGERPRINT",
			"PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN", "PLOT_CERTIFICATION_WORKSPACE_ID",
			"PLOT_CERTIFICATION_WRITING_BLOCK_IDS", "PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST",
		),
	)
}

tasks.register<JavaExec>("generationCertificationProfile") {
	description = "Derives the exact two-model request profile hashes without provider credentials"
	certificationTool(
		"com.plot.api.certification.CertificationGenerationProfileCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_AI_ROUTING_PROVIDER", "PLOT_AI_TIMEOUT_SECONDS", "PLOT_AI_MAX_OUTPUT_TOKENS",
			"PLOT_AI_TRANSPORT_RETRIES", "PLOT_AI_SCHEMA_RETRIES", "PLOT_CERTIFICATION_PROFILE_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationSealVerification") {
	description = "Seals the imported source hash, campaign, and two fixed model executions"
	certificationTool(
		"com.plot.api.certification.CertificationSealVerificationCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT", "PLOT_CERTIFICATION_CAMPAIGN_ID", "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST",
			"PLOT_CERTIFICATION_NANO_MANIFEST", "PLOT_CERTIFICATION_MINI_MANIFEST",
			"PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationCampaignSeal") {
	description = "Seals an imported-source campaign before the live model preflight"
	certificationTool(
		"com.plot.api.certification.CertificationCampaignSealVerificationCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT", "PLOT_CERTIFICATION_CAMPAIGN_ID",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST", "PLOT_CERTIFICATION_SEALED_CAMPAIGN_OUTPUT",
			"PLOT_CERTIFICATION_CORPUS_HASH", "PLOT_CERTIFICATION_PROFILE_HASH",
			"PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationReconcile") {
	description = "Offline-reconciles a browser observation with its persisted-audit envelope"
	certificationTool(
		"com.plot.api.certification.CertificationReconcileCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST", "PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST",
			"PLOT_CERTIFICATION_BROWSER_OBSERVATION", "PLOT_CERTIFICATION_AUDIT_ENVELOPE",
			"PLOT_CERTIFICATION_RECONCILIATION_OUTPUT", "PLOT_CERTIFICATION_REPLACES_ATTEMPT_ID",
			"PLOT_CERTIFICATION_MODEL_REPLACEMENT_RESULT",
			"PLOT_CERTIFICATION_BROWSER_TERMINAL_ONLY",
		),
	)
}

tasks.register<JavaExec>("generationCertificationReport") {
	description = "Assembles and renders a redacted report from sealed immutable evidence"
	certificationTool(
		"com.plot.api.certification.CertificationReportCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_REPORT_PHASE", "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST",
			"PLOT_CERTIFICATION_NANO_MANIFEST", "PLOT_CERTIFICATION_MINI_MANIFEST",
			"PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY", "PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY",
			"PLOT_CERTIFICATION_DETERMINISTIC_RESULT", "PLOT_CERTIFICATION_RESTART_RESULT",
			"PLOT_CERTIFICATION_CLEANUP_OUTPUT", "PLOT_CERTIFICATION_OPERATOR_DECISION",
			"PLOT_CERTIFICATION_REPORT_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationRestartSelection") {
	description = "Selects the restart candidate through the report assembler's validated model/browser core"
	certificationTool(
		"com.plot.api.certification.CertificationRestartSelectionCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST", "PLOT_CERTIFICATION_NANO_MANIFEST",
			"PLOT_CERTIFICATION_MINI_MANIFEST", "PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY",
			"PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY", "PLOT_CERTIFICATION_RESTART_SELECTION_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationReportSnapshot") {
	description = "Creates a redacted report snapshot before raw certification evidence is purged"
	certificationTool(
		"com.plot.api.certification.CertificationReportSnapshotCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST", "PLOT_CERTIFICATION_NANO_MANIFEST",
			"PLOT_CERTIFICATION_MINI_MANIFEST", "PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY",
			"PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY", "PLOT_CERTIFICATION_DETERMINISTIC_RESULT",
			"PLOT_CERTIFICATION_RESTART_RESULT", "PLOT_CERTIFICATION_OPERATOR_DECISION",
			"PLOT_CERTIFICATION_REPORT_SNAPSHOT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationFinalReport") {
	description = "Renders the final report only from a redacted snapshot and post-purge cleanup proof"
	certificationTool(
		"com.plot.api.certification.CertificationFinalReportCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_REPORT_SNAPSHOT", "PLOT_CERTIFICATION_REPORT_SNAPSHOT_HASH",
			"PLOT_CERTIFICATION_CLEANUP_OUTPUT", "PLOT_CERTIFICATION_OPERATOR_DECISION",
			"PLOT_CERTIFICATION_REPORT_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationCleanup") {
	description = "Evaluates machine-observed cleanup and attributed credential disposition before final GO"
	certificationTool(
		"com.plot.api.certification.CertificationCleanupCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_CAMPAIGN_ID", "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH", "PLOT_GENERATION_SOURCE_REVISION",
			"PLOT_CERTIFICATION_CLEANUP_RECORDED_AT", "PLOT_CERTIFICATION_OPERATOR_ALIAS", "PLOT_CERTIFICATION_CLEANUP_ATTESTED_AT",
			"PLOT_CERTIFICATION_LISTENER_COUNT", "PLOT_CERTIFICATION_GITHUB_CREDENTIAL_REVOKED",
			"PLOT_CERTIFICATION_OPENROUTER_CREDENTIAL_REVOKED", "PLOT_CERTIFICATION_STATE_SECRET_DISPOSED",
			"PLOT_CERTIFICATION_RAW_ARTIFACTS_DELETED", "PLOT_CERTIFICATION_BROWSER_ARTIFACTS_DELETED",
			"PLOT_CERTIFICATION_DATABASE_DISPOSITION", "PLOT_CERTIFICATION_RETAINED_OWNER_ALIAS",
			"PLOT_CERTIFICATION_RETAINED_EXPIRES_AT", "PLOT_CERTIFICATION_CLEANUP_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationRestartApi") {
	description = "Starts the certification-only API launcher and pauses after one durable checkpoint"
	group = "verification"
	dependsOn(tasks.named("compileCertificationKotlin"))
	mainClass.set("com.plot.api.certification.CertificationRestartApiLauncher")
	classpath = certification.runtimeClasspath
	setEnvironment(System.getenv().filterKeys { name ->
		name in setOf(
			"PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ",
			"SPRING_PROFILES_ACTIVE", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
			"SERVER_ADDRESS", "SERVER_PORT", "MANAGEMENT_SERVER_ADDRESS", "MANAGEMENT_SERVER_PORT",
			"PLOT_CERTIFICATION_MANAGEMENT_SERVER_ADDRESS",
			"PLOT_DEV_BOOTSTRAP_ENABLED", "PLOT_AI_ENABLED", "PLOT_AI_PROVIDER", "PLOT_AI_MODEL",
			"PLOT_LOOPBACK_REQUEST_GUARD_ENABLED", "PLOT_GITHUB_ENABLED",
			"PLOT_AI_BASE_URL", "PLOT_AI_ROUTING_PROVIDER", "PLOT_AI_ALLOW_FALLBACKS", "PLOT_AI_REQUIRE_PARAMETERS",
			"PLOT_AI_ZERO_DATA_RETENTION", "PLOT_AI_CONTENT_LOGGING_ENABLED", "PLOT_AI_CLAIM_TIMEOUT", "PLOT_AI_TIMEOUT",
			"PLOT_AI_TRANSPORT_RETRIES", "PLOT_AI_SCHEMA_RETRIES", "PLOT_AI_MAX_OUTPUT_TOKENS",
			"PLOT_AI_MAX_MODEL_CALLS", "PLOT_AI_MAX_TOTAL_TOKENS",
			"PLOT_AI_MAX_RUN_DURATION", "SPRING_AI_MODEL_CHAT", "SPRING_AI_OPENAI_API_KEY",
			"SPRING_AI_OPENAI_MAX_RETRIES", "SPRING_AI_CHAT_OBSERVATIONS_LOG_PROMPT",
			"SPRING_AI_CHAT_OBSERVATIONS_LOG_COMPLETION", "SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_PROMPT",
			"SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_COMPLETION",
			"PLOT_GENERATION_CERTIFICATION_TOOL", "PLOT_CERTIFICATION_CAMPAIGN_ID",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH", "PLOT_CERTIFICATION_MODEL_EXECUTION_ID",
			"PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH", "PLOT_CERTIFICATION_ATTEMPT_ID",
			"PLOT_CERTIFICATION_RESTART_CHECKPOINT", "PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH",
			"PLOT_CERTIFICATION_RESTART_MARKER", "PLOT_CERTIFICATION_OUTPUT_ROOT",
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_NAME",
			"PLOT_CERTIFICATION_DATABASE_FINGERPRINT", "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN",
		)
	})
	doFirst {
		require(System.getenv("PLOT_GENERATION_CERTIFICATION_TOOL").equals("true", ignoreCase = true)) {
			"Set PLOT_GENERATION_CERTIFICATION_TOOL=true to opt in"
		}
	}
}

tasks.register<JavaExec>("generationCertificationRestartState") {
	description = "Extracts one allow-listed durable state around the process restart"
	certificationTool(
		"com.plot.api.certification.CertificationRestartEvidenceExtractor",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_DATABASE_URL", "PLOT_CERTIFICATION_DATABASE_NAME",
			"PLOT_CERTIFICATION_DATABASE_USERNAME", "PLOT_CERTIFICATION_DATABASE_PASSWORD",
			"PLOT_CERTIFICATION_DATABASE_FINGERPRINT", "PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN",
			"PLOT_CERTIFICATION_IDEMPOTENCY_KEY", "PLOT_CERTIFICATION_CAMPAIGN_ID",
			"PLOT_CERTIFICATION_CAMPAIGN_MANIFEST", "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH",
			"PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST", "PLOT_CERTIFICATION_MODEL_EXECUTION_ID",
			"PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH", "PLOT_CERTIFICATION_ATTEMPT_ID",
			"PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH", "PLOT_CERTIFICATION_RESTART_CHECKPOINT",
			"PLOT_CERTIFICATION_RESTART_STATE_OUTPUT",
		),
	)
}

tasks.register<JavaExec>("generationCertificationRestartReconcile") {
	description = "Reconciles pre-stop and post-restart durable state"
	certificationTool(
		"com.plot.api.certification.CertificationRestartReconcileCli",
		commonCertificationToolEnvironment + setOf(
			"PLOT_CERTIFICATION_RESTART_BEFORE", "PLOT_CERTIFICATION_RESTART_AFTER", "PLOT_CERTIFICATION_RESTART_RESULT",
		),
	)
}

val bootJarTask = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")

tasks.register("verifyCertificationIsolation") {
	description = "Proves certification-only classes and resources are absent from the production bootJar"
	group = "verification"
	dependsOn(bootJarTask)
	doLast {
		val forbiddenEntries = zipTree(bootJarTask.get().archiveFile.get().asFile).matching {
			include("BOOT-INF/classes/com/plot/api/certification/**")
			include("BOOT-INF/classes/generation-workflow-cases.json")
		}.files
		check(forbiddenEntries.isEmpty()) {
			"Certification implementation leaked into the production bootJar"
		}
	}
}
