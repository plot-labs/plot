plugins {
	kotlin("jvm") version "2.4.0"
	kotlin("plugin.spring") version "2.4.0"
	kotlin("plugin.serialization") version "2.4.0"
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
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

extra["koogVersion"] = "1.2.0"
extra["kotlin.version"] = "2.4.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql:11.14.1")
	implementation("org.jetbrains.exposed:exposed-java-time:1.5.0")
	implementation("org.jetbrains.exposed:exposed-spring-boot4-starter:1.5.0")
	implementation("com.workos:workos:7.1.0")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("ai.koog:agents-core:${property("koogVersion")}")
	implementation("ai.koog:prompt-executor-openrouter-client:${property("koogVersion")}")
	implementation("ai.koog:http-client-java:${property("koogVersion")}")
	implementation("ai.koog:skills:${property("koogVersion")}-beta")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("io.micrometer:micrometer-observation-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
	test {
		resources.srcDir(file("../../contracts/plot-api/v1"))
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.add("-Xjsr305=strict")
	}
}
tasks.named<Test>("test") {
	useJUnitPlatform {
		excludeTags("live-eval")
	}
	environment("SPRING_PROFILES_ACTIVE", "test")
}

tasks.register<Test>("liveEval") {
	group = "verification"
	description = "Runs live citation/generation quality eval tests (requires AI credentials and PLOT_EVAL_LIVE=true)"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	useJUnitPlatform {
		includeTags("live-eval")
	}
	
	doFirst {
		val requiredEnvVars = listOf(
			"PLOT_AI_MODEL",
			"PLOT_AI_ROUTING_PROVIDER",
			"PLOT_AI_API_KEY",
		)
		val missing = requiredEnvVars.filter { System.getenv(it).isNullOrBlank() }
		if (missing.isNotEmpty()) {
			throw GradleException(
				"liveEval requires AI configuration environment variables: ${missing.joinToString(", ")}\n" +
					"Example:\n" +
					"  PLOT_AI_MODEL=openai/gpt-4o-mini-2024-07-18\n" +
					"  PLOT_AI_ROUTING_PROVIDER=openai\n" +
					"  PLOT_AI_API_KEY=<your-api-key>",
			)
		}
	}
	
	environment("SPRING_PROFILES_ACTIVE", "test")
	environment("PLOT_EVAL_LIVE", "true")
	systemProperty("plot.ai.enabled", "true")
}
