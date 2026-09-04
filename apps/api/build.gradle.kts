buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		classpath("org.flywaydb:flyway-database-postgresql:11.14.1")
		classpath("org.postgresql:postgresql:42.7.11")
	}
}

plugins {
	kotlin("jvm") version "2.4.0"
	kotlin("plugin.spring") version "2.4.0"
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.jooq.jooq-codegen-gradle") version "3.19.35"
	id("org.flywaydb.flyway") version "11.14.1"
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
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql:11.14.1")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("io.micrometer:micrometer-observation-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val jooqCodegenJdbcUrl = System.getenv("JOOQ_CODEGEN_JDBC_URL") ?: "jdbc:postgresql://localhost:5433/plot"
val jooqCodegenJdbcUser = System.getenv("JOOQ_CODEGEN_JDBC_USER") ?: "plot"
val jooqCodegenJdbcPassword = System.getenv("JOOQ_CODEGEN_JDBC_PASSWORD") ?: "secret"
val jooqOutputDirectory = providers.gradleProperty("jooqOutputDir")
	.orElse(file("src/generated/kotlin").absolutePath)
	.get()

flyway {
	url = jooqCodegenJdbcUrl
	user = jooqCodegenJdbcUser
	password = jooqCodegenJdbcPassword
	locations = arrayOf("filesystem:${file("src/main/resources/db/migration").absolutePath}")
	configurations = arrayOf("runtimeClasspath")
}

jooq {
	configuration {
		logging = org.jooq.meta.jaxb.Logging.WARN
		jdbc {
			driver = "org.postgresql.Driver"
			url = jooqCodegenJdbcUrl
			user = jooqCodegenJdbcUser
			password = jooqCodegenJdbcPassword
		}
		generator {
			name = "org.jooq.codegen.KotlinGenerator"
			database {
				name = "org.jooq.meta.postgres.PostgresDatabase"
				inputSchema = "public"
				excludes = "flyway_schema_history"
			}
			generate {
				isPojos = false
				isDaos = false
				isGeneratedAnnotation = false
				isRelations = true
				isRecords = true
			}
			target {
				packageName = "com.plot.api.persistence.generated"
				directory = jooqOutputDirectory
				encoding = "UTF-8"
			}
		}
	}
}

tasks.named("jooqCodegen") {
	dependsOn("flywayMigrate")
	doFirst {
		System.setProperty("user.language", "en")
		System.setProperty("user.country", "US")
		System.setProperty("user.timezone", "UTC")
	}
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

sourceSets {
	test {
		resources.srcDir(file("../../contracts/plot-api/v1"))
	}
}

kotlin {
	sourceSets {
		getByName("main").kotlin.srcDir("src/generated/kotlin")
	}
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
	useJUnitPlatform {
		includeTags("live-eval")
	}
	environment("SPRING_PROFILES_ACTIVE", "test")
	environment("PLOT_EVAL_LIVE", "true")
}
